package cn.changjiahong.composexmlform.engine.node

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.syntax.parseExpression
import cn.changjiahong.composexmlform.engine.syntax.parseLambda
import cn.changjiahong.composexmlform.engine.value.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 节点层的结构断言：属性声明、写法矩阵、子节点规则、结构化 id。
 *
 * 这一层**不求值** —— 求值在 `AttrReadTest`。分开测是因为 node 包刻意不依赖 env：
 * 一旦测试里需要造环境，就说明依赖方向错了。
 */
class NodeTest {

    // ── 属性声明 ─────────────────────────────────────────────

    @Test
    fun defaultValueFallsBackToTypeEmpty() {
        assertEquals("", Attr.string("text").defaultValue(), "字符串的类型空值是空串")
        assertNull(Attr.int("size").defaultValue(), "数字没有有意义的空值")
        assertEquals(14L, Attr.int("size", default = 14L).defaultValue(), "声明值优先")
        assertEquals(true, Attr.bool("bold", default = true).defaultValue())
        assertNull(Attr.func("onClick").defaultValue(), "函数槽位不给默认值")
    }

    @Test
    fun missingOptionalReadsAsAbsentSlot() {
        val slot = attrSet().attr(Attr.string("text"))
        assertFalse(slot.present)
        assertFalse(slot.isBinding)
        assertNull(slot.boundPath)
    }

    @Test
    fun missingRequiredIsReported() {
        val diagnostics = mutableListOf<Diagnostic>()
        attrSet(sink = diagnostics).attr(Attr.string("text", required = true))

        val diagnostic = diagnostics.single()
        assertEquals(Code.MissingRequiredAttribute, diagnostic.code)
        assertEquals("text", diagnostic.attribute)
        assertTrue(diagnostic.isError)
    }

    @Test
    fun unknownAttributeSuggestsNearestName() {
        val diagnostics = mutableListOf<Diagnostic>()
        val set = attrSet(
            entries = entriesOf("txet" to AttrRef.Literal("hi")),
            declared = listOf(Attr.string("text")),
            sink = diagnostics,
        )
        set.finish()

        val diagnostic = diagnostics.single()
        assertEquals(Code.UnknownAttribute, diagnostic.code)
        assertEquals("txet", diagnostic.attribute)
        assertEquals("你是不是想写『text』", diagnostic.hint)
    }

    @Test
    fun declaredButUnreadAttributeIsNotUnknown() {
        val diagnostics = mutableListOf<Diagnostic>()
        val set = attrSet(
            entries = entriesOf("text" to AttrRef.Literal("hi")),
            declared = listOf(Attr.string("text")),
            sink = diagnostics,
        )
        set.finish()

        assertTrue(diagnostics.isEmpty(), "声明过但没读，不算未知属性")
    }

    @Test
    fun rawOnlyReturnsLiteralText() {
        val set = attrSet(
            entries = entriesOf(
                "payload" to AttrRef.Literal("{\"a\":1}"),
                "onClick" to AttrRef.Lambda(parseLambda("click()")),
            ),
        )
        assertEquals("{\"a\":1}", set.raw("payload"))
        assertNull(set.raw("onClick"), "脚本片段不交给宿主解析")
    }

    // ── 槽位 × 写法矩阵（九格）────────────────────────────────

    @Test
    fun valSlotAcceptsAllButLambda() {
        val def = Attr.string("text")
        assertTrue(codes(def, AttrRef.Literal("a")).isEmpty())
        assertTrue(codes(def, AttrRef.Bind(path("data.name"))).isEmpty())
        assertTrue(codes(def, AttrRef.Code(parseExpression("1 + 2"))).isEmpty())
        assertEquals(listOf(Code.InvalidAttributeValue), codes(def, AttrRef.Lambda(parseLambda("f()"))))
    }

    @Test
    fun varSlotOnlyAcceptsBinding() {
        val def = Attr.varString("value")
        assertEquals(listOf(Code.LiteralInWritableSlot), codes(def, AttrRef.Literal("hello")))
        assertTrue(codes(def, AttrRef.Bind(path("data.name"))).isEmpty())
        assertEquals(listOf(Code.ReadOnlySlot), codes(def, AttrRef.Code(parseExpression("1"))))
        assertEquals(listOf(Code.ReadOnlySlot), codes(def, AttrRef.Lambda(parseLambda("f()"))))
    }

    @Test
    fun functionSlotOnlyAcceptsLambda() {
        val def = Attr.func("onClick")
        assertEquals(listOf(Code.InvalidAttributeValue), codes(def, AttrRef.Literal("login")))
        assertEquals(listOf(Code.InvalidAttributeValue), codes(def, AttrRef.Bind(path("data.fn"))))
        assertEquals(listOf(Code.InvalidAttributeValue), codes(def, AttrRef.Code(parseExpression("1"))))
        assertTrue(codes(def, AttrRef.Lambda(parseLambda("click()"))).isEmpty())
    }

    @Test
    fun unwrittenSlotNeverConflicts() {
        // 没写属性时，三种槽位都不该报「写法不对」—— 缺不缺值是 required 的事
        assertTrue(codes(Attr.string("text"), AttrRef.Absent).isEmpty())
        assertTrue(codes(Attr.varString("value"), AttrRef.Absent).isEmpty())
        assertTrue(codes(Attr.func("onClick"), AttrRef.Absent).isEmpty())
    }

    // ── 子节点规则 ────────────────────────────────────────────

    @Test
    fun childRuleNoneRejectsChildren() {
        assertNull(ChildRule.None.check(emptyList()))
        val violation = ChildRule.None.check(listOf("Text"))!!
        assertEquals(Code.ChildNotAllowed, violation.code)
    }

    @Test
    fun childRuleAtLeastOneRequiresChildren() {
        assertEquals(Code.ChildRequired, ChildRule.AtLeastOne.check(emptyList())!!.code)
        assertNull(ChildRule.AtLeastOne.check(listOf("Text")))
    }

    @Test
    fun childRuleOnlyChecksTags() {
        val rule = ChildRule.Only(setOf("Then", "Else"))
        assertNull(rule.check(listOf("Then", "Else")))
        assertNull(rule.check(emptyList()), "Only 不管数量，只管标签")
        val violation = rule.check(listOf("Then", "Other"))!!
        assertEquals(Code.ChildTagMismatch, violation.code)
        assertTrue(violation.message.contains("Other"))
    }

    @Test
    fun childRuleAnyAcceptsAnything() {
        assertNull(ChildRule.Any.check(emptyList()))
        assertNull(ChildRule.Any.check(listOf("随便", "什么都行")))
    }

    // ── 结构化 id ─────────────────────────────────────────────

    @Test
    fun structuralIdsFollowTreePosition() {
        val root = NodeIds.root()
        val first = NodeIds.child(root, 0)
        val second = NodeIds.child(first, 2)

        assertEquals("0", root)
        assertEquals("0.0", first)
        assertEquals("0.0.2", second)
        assertEquals(3, NodeIds.depth(second))
        assertEquals(2, NodeIds.indexOf(second))
        assertEquals("0.0", NodeIds.parentOf(second))
        assertNull(NodeIds.indexOf(root))
        assertNull(NodeIds.parentOf(root))
        assertTrue(NodeIds.isUnder(second, root))
        assertTrue(NodeIds.isUnder(second, first))
        assertFalse(NodeIds.isUnder(root, second))
    }

    // ── 注册表 ────────────────────────────────────────────────

    @Test
    fun nodeTypesRejectDuplicateTag() {
        val types = NodeTypes.of(nodeType("Text"))
        assertTrue(types.contains("Text"))

        assertFailsWith<IllegalArgumentException> { types.plus(nodeType("Text")) }
    }

    @Test
    fun nodeTypesCanReplaceAndRemove() {
        val original = NodeTypes.of(nodeType("Text", attrs = listOf(Attr.string("text"))))
        val replaced = original.replacing(nodeType("Text", attrs = listOf(Attr.string("value"))))

        assertEquals("value", replaced["Text"]!!.attributes.single().name)
        assertEquals("text", original["Text"]!!.attributes.single().name, "不可变：原表不动")
        assertNull(replaced.minus("Text")["Text"])
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private fun attrSet(
        tag: String = "Text",
        entries: Map<String, AttrEntry> = emptyMap(),
        declared: List<AttrDef<*>> = emptyList(),
        sink: MutableList<Diagnostic> = mutableListOf(),
    ): AttrSet = AttrSet(tag, Span.None, entries, declared, sink)

    private fun entriesOf(vararg pairs: Pair<String, AttrRef>): Map<String, AttrEntry> =
        pairs.associate { (name, ref) -> name to AttrEntry(ref, Span.None) }

    private fun path(raw: String): Path = Path.parse(raw)!!.withRoot(raw.substringBefore('.'))

    /** 取「这个写法在某个槽位上会报哪些码」。 */
    private fun <T> codes(def: AttrDef<T>, ref: AttrRef): List<Code> {
        val diagnostics = mutableListOf<Diagnostic>()
        val set = attrSet(
            entries = entriesOf(def.name to ref),
            declared = listOf(def),
            sink = diagnostics,
        )
        set.attr(def)
        set.finish()
        return diagnostics.map { it.code }
    }

    private fun nodeType(tag: String, attrs: List<AttrDef<*>> = emptyList()): NodeType =
        NodeType(tag, attrs) { id, _, _ -> object : Node(id) {} }
}
