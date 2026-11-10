package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.EvalError
import cn.changjiahong.composexmlform.engine.diag.SyntaxError
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.syntax.parseExpression
import cn.changjiahong.composexmlform.engine.syntax.parseStatements
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.ChangeChannel
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataChange
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Fn
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.MutableDataObject
import cn.changjiahong.composexmlform.engine.value.Path
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.ValueKind
import cn.changjiahong.composexmlform.engine.value.WriteSource
import cn.changjiahong.composexmlform.engine.value.dataObjectOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 求值器：运算符、名字解析、赋值、方法查找、`await`。
 *
 * 每一条都对应 DECISIONS 里的一个编号 —— 这些不是「顺手测一下」，
 * 而是把「为什么这么设计」钉成可执行的断言。
 */
class EvalTest {

    private val frozenEmpty = DataObject.frozen(emptyMap())

    private fun context(
        data: DataObject = dataObjectOf(),
        config: DataObject = frozenEmpty,
        action: DataObject = frozenEmpty,
        extra: Map<String, DataObject> = emptyMap(),
        methods: MethodExtensions = MethodExtensions.Empty,
    ): EvalContext {
        val objects = buildMap<String, DataObject> {
            put("data", data)
            put("config", config)
            put("action", action)
            putAll(extra)
        }
        return EvalContext(Env.root(objects), methods = methods)
    }

    /** 属性表达式：同步、只读、没有帧。 */
    private fun EvalContext.value(source: String): Value =
        Evaluator.evaluate(parseExpression(source), this)

    /** 脚本：有帧，可声明变量、可赋值。 */
    private fun EvalContext.script(source: String): Value =
        Evaluator.runBody(parseStatements(source), withFrame(CallFrame()))

    // ── 算术 ──────────────────────────────────────────────────

    @Test
    fun precedenceFollowsKotlin() {
        val ctx = context()
        assertEquals(7L, ctx.value("1 + 2 * 3"))
        assertEquals(9L, ctx.value("(1 + 2) * 3"))
        assertEquals(1L, ctx.value("7 % 3"))
    }

    @Test
    fun divisionAlwaysYieldsDouble() {
        val ctx = context()
        assertEquals(3.5, ctx.value("7 / 2"), "P-6：整数除法不是 3")
        assertEquals(3.0, ctx.value("6 / 2"), "能整除也是 Double")
    }

    @Test
    fun mixedNumbersWidenToDouble() {
        val ctx = context()
        assertEquals(3.0, ctx.value("1 + 2.0"))
        assertEquals(2.5, ctx.value("5.0 / 2"))
    }

    @Test
    fun stringPlusAnythingConcatenates() {
        val ctx = context(data = dataObjectOf("name" to "张三", "count" to 3))
        assertEquals("ni张三", ctx.value("'ni' + data.name"))
        assertEquals("共3", ctx.value("'共' + data.count"), "数字按显示规则转文本，不出现 3.0")
    }

    @Test
    fun numberPlusStringIsRejected() {
        val ctx = context()
        val error = assertFailsWith<EvalError> { ctx.value("1 + 'a'") }
        assertTrue(error.message!!.contains("必须是数字"), error.message!!)
    }

    // ── Absent / null（P-4）──────────────────────────────────

    @Test
    fun absentPropagatesAlongPath() {
        val ctx = context(data = dataObjectOf("a" to mapOf("b" to mapOf("c" to 1))))
        assertEquals(Absent, ctx.value("data.x.y.z"), "三级缺失仍是一串 Absent，不报错")
        assertEquals(1L, ctx.value("data.a.b.c"))
        assertEquals(Absent, ctx.value("data.a.b.c.d"), "到标量上继续下钻也是 Absent")
    }

    @Test
    fun absentInOperationReportsItsOwnColumn() {
        val ctx = context()
        val error = assertFailsWith<EvalError> { ctx.value("1 + data.missing") }
        assertEquals(5, error.span?.column, "报到操作数起点，不是整条表达式起点")
        assertTrue(error.message!!.contains("路径不存在"), error.message!!)
    }

    @Test
    fun nullInOperationFails() {
        val ctx = context(data = dataObjectOf("n" to null))
        val error = assertFailsWith<EvalError> { ctx.value("data.n + 1") }
        assertTrue(error.message!!.contains("null"), error.message!!)
    }

    @Test
    fun elvisOnlyTreatsNullAndAbsentAsMissing() {
        val ctx = context(
            data = dataObjectOf("zero" to 0, "empty" to "", "off" to false),
        )
        assertEquals(0L, ctx.value("data.zero ?: 5"), "0 不是缺失")
        assertEquals("", ctx.value("data.empty ?: 'x'"), "空串不是缺失")
        assertEquals(false, ctx.value("data.off ?: true"), "false 不是缺失")
        assertEquals(5L, ctx.value("data.none ?: 5"))
    }

    // ── 布尔（P-3）──────────────────────────────────────────

    @Test
    fun conditionMustBeBoolean() {
        val ctx = context(data = dataObjectOf("ok" to true, "list" to listOf(1, 2)))
        assertEquals(1L, ctx.value("if (data.ok) 1 else 2"))
        assertEquals(2L, ctx.value("if (!data.ok) 1 else 2"))
        val error = assertFailsWith<EvalError> { ctx.value("if (data.list) 1 else 2") }
        assertTrue(error.message!!.contains("布尔"), error.message!!)
    }

    @Test
    fun logicalOperatorsShortCircuit() {
        val ctx = context(data = dataObjectOf("off" to false, "on" to true))
        // `boom` 不存在：右侧若被求值就会抛「不存在」
        assertEquals(false, ctx.value("data.off && boom()"))
        assertEquals(true, ctx.value("data.on || boom()"))
    }

    @Test
    fun ifWithoutElseYieldsNull() {
        val ctx = context(data = dataObjectOf("ok" to false))
        assertEquals(null, ctx.value("if (data.ok) 1"))
    }

    // ── 相等（K-2）──────────────────────────────────────────

    @Test
    fun equalityIsStructural() {
        val ctx = context(
            data = dataObjectOf(
                "a" to mapOf("x" to 1, "y" to listOf(1, 2)),
                "b" to mapOf("x" to 1, "y" to listOf(1, 2)),
            ),
        )
        assertEquals(true, ctx.value("data.a == data.b"))
        assertEquals(true, ctx.value("2 == 2.0"), "数字跨类型按数值比")
        assertEquals(false, ctx.value("data.a == data.a.x"), "不同形态不相等")
    }

    @Test
    fun tripleEqualsIsReference() {
        val ctx = context(data = dataObjectOf("a" to mapOf("x" to 1), "b" to mapOf("x" to 1)))
        assertEquals(false, ctx.value("data.a === data.b"))
        assertEquals(true, ctx.value("data.a === data.a"))
        assertEquals(true, ctx.value("data.a !== data.b"))
    }

    // ── 语句与作用域 ─────────────────────────────────────────

    @Test
    fun varIsReassignableAndValIsNot() {
        val ctx = context()
        assertEquals(2L, ctx.script("var x = 1; x = 2"))
        val error = assertFailsWith<EvalError> { ctx.script("val x = 1; x = 2") }
        assertTrue(error.message!!.contains("val"), error.message!!)
    }

    @Test
    fun duplicateDeclarationIsRejected() {
        val ctx = context()
        val error = assertFailsWith<EvalError> { ctx.script("val x = 1; val x = 2") }
        assertTrue(error.message!!.contains("已经在本层定义"), error.message!!)
    }

    @Test
    fun localShadowsDataButDataFieldStaysReadable() {
        val data = dataObjectOf("name" to "来自 data")
        val ctx = context(data = data)
        assertEquals("来自 data", ctx.value("name"), "没有局部量时兜底到 data")
        assertEquals("来自 data", ctx.value("data.name"), "绝对路径永远指向 data")
        assertEquals("局部", ctx.script("val name = '局部'; name"))
    }

    @Test
    fun undeclaredAssignmentFallsBackToData() {
        val data = dataObjectOf()
        val ctx = context(data = data)
        ctx.script("nickname = '阿四'")
        assertEquals("阿四", data.member("nickname"), "K-7：写进 data")
    }

    @Test
    fun blockReturnsLastValue() {
        val ctx = context(data = dataObjectOf("ok" to true))
        assertEquals(2L, ctx.script("if (data.ok) { 1; 2 } else { 0 }"))
    }

    @Test
    fun returnStopsExecution() {
        val ctx = context()
        assertEquals(1L, ctx.script("val f = @{ x -> if (x) return 1; 2 }; f(true)"))
        assertEquals(2L, ctx.script("val f = @{ x -> if (x) return 1; 2 }; f(false)"))
    }

    // ── 写入 ─────────────────────────────────────────────────

    private class RecordingChannel : ChangeChannel {
        val events = mutableListOf<DataChange>()
        override fun emit(change: DataChange) {
            events += change
        }
    }

    private fun rootedEnv(
        channel: RecordingChannel,
        vararg pairs: Pair<String, Any?>,
    ): Pair<MutableDataObject, Env> {
        val root = MutableDataObject(frozen = false, channel = channel)
        for ((key, value) in pairs) root.setMember(key, value)
        return root to Env.root(mapOf("data" to root, "action" to frozenEmpty))
    }

    @Test
    fun scriptAssignmentEmitsChangeEvent() {
        val channel = RecordingChannel()
        val (_, env) = rootedEnv(channel, "name" to "张三")
        EvalContext(env).script("name = '李四'")

        val event = channel.events.single()
        assertEquals("name", event.path, "I-1：路径相对 data 根")
        assertEquals(WriteSource.Script, event.source)
    }

    @Test
    fun referenceWriteGoesBackThroughOrigin() {
        val channel = RecordingChannel()
        val (root, env) = rootedEnv(channel, "user" to mapOf("name" to "张三"))
        // Foreach 的行变量、或 `val u = data.user` 这类绑定：靠 origin 记住来路（E-2）
        env.define("u", env.read("data.user")!!, origin = Path.absolute("data", "user"))

        EvalContext(env).script("u.name = '李四'")

        assertEquals("user.name", channel.events.single().path)
        assertEquals("李四", (root.member("user") as DataObject).member("name"))
    }

    @Test
    fun writingFrozenObjectFails() {
        val ctx = context(config = DataObject.frozen(mapOf("title" to "x")))
        assertFailsWith<cn.changjiahong.composexmlform.engine.diag.FrozenWriteException> {
            ctx.script("config.title = 'y'")
        }
    }

    @Test
    fun detachedObjectIsMutableThroughLocalReference() {
        val ctx = context()
        assertEquals(2L, ctx.script("val o = { a: 1 }; o.a = 2; o.a"), "不落在 data 里的临时对象直接改")
    }

    // ── 函数 ─────────────────────────────────────────────────

    @Test
    fun lambdaIsAValueThatSeesLiveData() {
        val data = dataObjectOf("x" to 1)
        val ctx = context(data = data)

        val fn = assertIs<Function>(ctx.value("@{ data.x }"))
        assertEquals(1L, fn.invoke(Absent, emptyList()))

        data.setMember("x", 2)
        assertEquals(2L, fn.invoke(Absent, emptyList()), "S-1：不捕获定义处的值")
    }

    @Test
    fun lambdaCanBeStoredInDataAndCalled() {
        val log = mutableListOf<String>()
        val action = DataObject.frozen(mapOf("log" to Fn.of1 { v -> log += showValue(v); null }))
        val data = dataObjectOf("user" to mapOf("name" to "张三"))
        val ctx = context(data = data, action = action)

        ctx.script("data.user.print = @{ action.log('x') }")
        assertIs<Function>((data.member("user") as DataObject).member("print"), "F-5：函数是一等值")

        ctx.script("data.user.print()")
        assertEquals(listOf("x"), log)
    }

    @Test
    fun lambdaParamsArePositional() {
        val ctx = context()
        assertEquals(3L, ctx.script("val f = @{ a, b -> a + b }; f(1, 2)"))
        assertEquals(Absent, ctx.script("val f = @{ a -> a }; f()"), "少给的实参是 Absent，不报错")
        assertEquals(1L, ctx.script("val f = @{ a -> a }; f(1, 2)"), "多给的实参忽略")
    }

    @Test
    fun arityMismatchIsReportedWithCounts() {
        val action = DataObject.frozen(mapOf("greet" to Fn.of1 { "hi" }))
        val ctx = context(action = action)

        val error = assertFailsWith<EvalError> { ctx.value("action.greet()") }
        assertTrue(error.message!!.contains("期望 1 个参数，收到 0 个"), error.message!!)
    }

    @Test
    fun missingFunctionSaysSo() {
        val ctx = context()
        val error = assertFailsWith<EvalError> { ctx.value("action.nope()") }
        assertTrue(error.message!!.contains("不存在"), error.message!!)
    }

    // ── 方法查找（F-2 / V-11）────────────────────────────────

    @Test
    fun arrayMethodReceivesTarget() {
        val data = dataObjectOf("list" to listOf(1, 2))
        val ctx = context(data = data)

        ctx.script("data.list.append(3)")
        val list = data.member("list")
        assertIs<DataArray>(list)
        assertEquals(3, list.size)
        assertEquals(3L, list.get(2))

        assertEquals(3L, ctx.value("data.list.size()"))
        assertEquals(1L, ctx.value("data.list.indexOf(2)"))
    }

    @Test
    fun arrayMethodChainReturnsArray() {
        val data = dataObjectOf("list" to listOf(1))
        val ctx = context(data = data)

        ctx.script("data.list.append(2).append(3)")
        assertEquals(3, (data.member("list") as DataArray).size)
    }

    @Test
    fun stringMethodsWork() {
        val data = dataObjectOf("name" to " 张三-李四 ")
        val ctx = context(data = data)

        assertEquals(" 张三-李四 ", ctx.value("data.name"), "绑定路径不做任何加工")
        assertEquals("张三-李四", ctx.value("data.name.trim()"))
        assertEquals(2L, ctx.value("data.name.trim().split('-').size()"))
        assertEquals(true, ctx.value("data.name.contains('李')"))
    }

    @Test
    fun dataFieldShadowsBuiltinMethod() {
        val data = dataObjectOf("obj" to mapOf("keys" to "我的键", "other" to 1))
        val ctx = context(data = data)

        assertEquals("我的键", ctx.value("data.obj.keys"), "数据字段优先（F-2）")

        val keys = assertIs<DataArray>(ctx.value("keys(data.obj)"))
        assertEquals(listOf("keys", "other"), keys.elements, "全局函数是字段遮蔽方法时的逃生舱")
    }

    @Test
    fun extensionChainSkipsAbstainingProvider() {
        val methods = methodExtensions {
            provide { value, name ->
                if (name == "double" && value is Long && value > 100) Fn.noArg { -1L } else null
            }
            provide { value, name ->
                if (name == "double" && value is Long) Fn.of { target, _ -> (target as Long) * 2 } else null
            }
        }
        val ctx = context(data = dataObjectOf("n" to 2, "big" to 200), methods = methods)

        assertEquals(4L, ctx.value("data.n.double()"), "第一个 provider 弃权，第二个命中")
        assertEquals(-1L, ctx.value("data.big.double()"))
    }

    @Test
    fun extensionCanTargetHostObjects() {
        val methods = methodExtensions {
            on(ValueKind.Object, "describe", Fn.of { target, _ -> (target as DataObject).keys().size.toLong() })
            onHost("raw") { host, _ -> (host.raw as StringBuilder).toString() }
        }
        val data = dataObjectOf("obj" to mapOf("a" to 1), "sb" to StringBuilder("hi"))
        val ctx = context(data = data, methods = methods)

        assertEquals(1L, ctx.value("data.obj.describe()"))
        assertEquals("hi", ctx.value("data.sb.raw()"))
    }

    @Test
    fun registeringBuiltinMethodNameFails() {
        val error = assertFailsWith<IllegalArgumentException> {
            methodExtensions { on(ValueKind.Array, "append", Fn.noArg { null }) }
        }
        assertTrue(error.message!!.contains("内置方法"), error.message!!)
    }

    // ── 全局函数 ─────────────────────────────────────────────

    @Test
    fun globalFunctionsCoverConversionAndInspection() {
        val ctx = context(data = dataObjectOf("list" to listOf(3, 1, 2), "obj" to mapOf("a" to 1)))

        assertEquals(3L, ctx.value("length(data.list)"))
        assertEquals("a", assertIs<DataArray>(ctx.value("keys(data.obj)")).elements.single())
        assertEquals(true, ctx.value("has(data.obj, 'a')"))
        assertEquals(false, ctx.value("has(data.obj, 'b')"))
        assertEquals(3L, ctx.value("int('3')"))
        assertEquals(2.5, ctx.value("num('2.5')"))
        assertEquals("3", ctx.value("str(3)"))
        assertEquals(true, ctx.value("equals(1, 1.0)"))
    }

    @Test
    fun copyMakesIndependentDraft() {
        val data = dataObjectOf("form" to mapOf("name" to "张三"))
        val ctx = context(data = data)

        val draft = assertIs<DataObject>(ctx.value("copy(data.form)"))
        ctx.script("data.form.name = '李四'")

        assertEquals("张三", draft.member("name"), "副本与原数据无关")
        assertEquals("李四", (data.member("form") as DataObject).member("name"))
    }

    @Test
    fun newCreatesEmptyObject() {
        val ctx = context()
        val created = assertIs<DataObject>(ctx.value("new()"))
        assertEquals(emptyList(), created.keys())
    }

    @Test
    fun jsonSerializesContainers() {
        val ctx = context(data = dataObjectOf("obj" to mapOf("a" to 1, "b" to "x\"y")))
        assertEquals("""{"a":1,"b":"x\"y"}""", ctx.value("json(data.obj)"))
    }

    // ── await（F-4 / P-5）────────────────────────────────────

    @Test
    fun suspendFunctionIsRejectedInAttributeContext() {
        val ctx = context(extra = mapOf("http" to DataObject.frozen(mapOf("get" to Fn.suspendOf1 { "ok" }))))

        val error = assertFailsWith<EvalError> { ctx.value("http.get('/u')") }
        assertTrue(error.message!!.contains("挂起上下文"), error.message!!)

        val statement = assertFailsWith<EvalError> { ctx.script("http.get('/u')") }
        assertTrue(statement.message!!.contains("await"), statement.message!!)
    }

    @Test
    fun awaitRunsSuspendFunction() = runTest {
        val ctx = context(
            extra = mapOf("http" to DataObject.frozen(mapOf("get" to Fn.suspendOf1 { "ok-$it" }))),
            action = DataObject.frozen(mapOf("now" to Fn.noArg { 7L })),
        )

        assertEquals("ok-/u", executeScript("val r = await http.get('/u'); r", ctx))
        assertEquals(7L, executeScript("await action.now()", ctx), "同步函数也允许 await")
        assertEquals(8L, executeScript("val r = await action.now(); r + 1", ctx))
    }

    @Test
    fun awaitOperandMustBeAFunctionCall() {
        assertFailsWith<SyntaxError> { parseStatements("await 1 + 2") }
        assertFailsWith<SyntaxError> { parseStatements("await x") }
        assertFailsWith<SyntaxError> { parseStatements("val r = await x") }
    }

    @Test
    fun awaitResultCannotBeUsedInline() {
        val error = assertFailsWith<SyntaxError> { parseStatements("await f(1) + 1") }
        assertTrue(error.message!!.contains("先赋给变量"), error.message!!)
    }

    // ── 杂项 ─────────────────────────────────────────────────

    @Test
    fun objectAndArrayLiteralsAreMutableValues() {
        val ctx = context()
        val obj = assertIs<DataObject>(ctx.value("{ a: 1, b: [1, 2] }"))
        assertEquals(1L, obj.member("a"))
        assertIs<DataArray>(obj.member("b"))

        val array = assertIs<DataArray>(ctx.value("[1, 2, 3]"))
        assertEquals(3, array.size)
    }

    @Test
    fun hostObjectIsNotDrillable() {
        val ctx = context(data = dataObjectOf("sb" to StringBuilder("hi")))
        assertEquals(Absent, ctx.value("data.sb.length"), "V-2：宿主对象不下钻")
    }

    @Test
    fun sameSlotIsReusedByRepeatedDefine() {
        val env = Env.root(mapOf("data" to dataObjectOf(), "action" to frozenEmpty))
        val first = env.define("item", 1)
        val second = env.define("item", 2)
        assertSame(first, second)
    }
}
