package cn.changjiahong.composexmlform.engine.env

import cn.changjiahong.composexmlform.engine.diag.FrozenWriteException
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.ChangeChannel
import cn.changjiahong.composexmlform.engine.value.DataChange
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Fn
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.HostObject
import cn.changjiahong.composexmlform.engine.value.MutableDataObject
import cn.changjiahong.composexmlform.engine.value.Path
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.WriteSource
import cn.changjiahong.composexmlform.engine.value.dataObjectOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 环境：一条链 + 一张命名全局表。
 *
 * 查找顺序、保留字、define/assign 分离、路径来源 —— 每一条都是后面所有层的前提。
 */
class EnvTest {

    private val emptyFrozen = DataObject.frozen(emptyMap())

    private fun env(
        data: DataObject = dataObjectOf(),
        config: DataObject = emptyFrozen,
        action: DataObject = DataObject.frozen(mapOf("login" to Fn.noArg { "ok" })),
        extra: Map<String, DataObject> = emptyMap(),
    ): Env {
        val objects: Map<String, DataObject> = buildMap {
            put("data", data)
            put("config", config)
            put("action", action)
            putAll(extra)
        }
        return Env.root(objects)
    }

    // ── 链 ────────────────────────────────────────────────────

    @Test
    fun innerLayerShadowsOuter() {
        val root = env(data = dataObjectOf("name" to "root"))
        root.define("name", "outer")
        val child = root.child().also { it.define("name", "inner") }

        assertEquals("inner", child.read("name"))
        assertEquals("outer", root.read("name"), "子层定义不影响父层")
        assertEquals("inner", child.chainFind("name")?.value, "chainFind 从自己开始，先命中内层")
    }

    @Test
    fun childSeesParentBindings() {
        val root = env()
        root.define("item", "row-1")
        val child = root.child()
        assertEquals("row-1", child.read("item"))
        assertNull(child.find("item"), "find 只看本层")
    }

    @Test
    fun defineDoesNotRemoveParentValue() {
        val root = env()
        val child = root.child()
        child.define("x", 1L)
        assertEquals(Absent, root.read("x"), "父层看不到子层变量")
    }

    // ── 保留字（E-6）─────────────────────────────────────────

    @Test
    fun definingReservedNameThrows() {
        val env = env()
        assertFailsWith<IllegalArgumentException> { env.define("data", 1L) }
        assertFailsWith<IllegalArgumentException> { env.child().define("config", 1L) }
    }

    @Test
    fun reservedNamesAreListedInSchema() {
        val schema = EnvSchema.from(mapOf("data" to dataObjectOf(), "pv" to dataObjectOf()))
        assertTrue(schema.isReserved("data"))
        assertTrue(schema.isReserved("pv"), "宿主注册的全局对象同样成为保留字（E-9）")
        assertFalse(schema.isReserved("user"))
    }

    // ── 绝对路径 ─────────────────────────────────────────────

    @Test
    fun absolutePathSkipsScopeChain() {
        val data = dataObjectOf("user" to mapOf("name" to "张三"))
        val env = env(data = data)
        env.define("user", "被劫持的值")

        assertEquals("张三", env.read("data.user.name"))
        assertEquals("被劫持的值", env.read("user"), "相对路径走链")
    }

    @Test
    fun absoluteReadOfUnknownGlobalIsAbsent() {
        val env = env()
        assertEquals(Absent, env.read(Path.absolute("nope", "x")))
    }

    // ── 相对路径 + 兜底 ──────────────────────────────────────

    @Test
    fun relativePathFallsBackToData() {
        val env = env(data = dataObjectOf("name" to "张三"))
        assertEquals("张三", env.read("name"))
    }

    @Test
    fun functionHintFallsBackToAction() {
        val env = env()
        val fn = env.read(Path.relative("login"), SlotKind.Function)
        assertIs<Function>(fn)
    }

    @Test
    fun namedGlobalIsReachableByRelativePath() {
        val env = env(extra = mapOf("pv" to dataObjectOf("name" to "page-1")))
        assertEquals("page-1", env.read("pv.name"))
    }

    @Test
    fun configIsReachableByRelativePath() {
        val env = env(config = DataObject.frozen(mapOf("title" to "标题")))
        assertEquals("标题", env.read("config.title"))
    }

    @Test
    fun unknownBareNameIsAbsent() {
        val env = env()
        assertEquals(Absent, env.read("usr.name"))
    }

    // ── 下降 ─────────────────────────────────────────────────

    @Test
    fun missingIntermediateReturnsAbsent() {
        val env = env(data = dataObjectOf("a" to mapOf("b" to mapOf("c" to 1))))
        assertEquals(Absent, env.read("data.a.x.c"))
        assertEquals(1L, env.read("data.a.b.c"))
    }

    @Test
    fun hostObjectIsNotDrillable() {
        val env = env(data = dataObjectOf("host" to StringBuilder("x")))
        assertEquals(Absent, env.read("data.host.name"))
        assertIs<HostObject>(env.read("data.host"))
    }

    @Test
    fun arrayIndexIsReadable() {
        val env = env(data = dataObjectOf("list" to listOf(1, 2, 3)))
        assertEquals(2L, env.read("data.list[1]"))
        assertEquals(Absent, env.read("data.list[9]"))
    }

    // ── 写：define / assign 分离（E-5）────────────────────────

    private class RecordingChannel : ChangeChannel {
        val events = mutableListOf<DataChange>()
        override fun emit(change: DataChange) {
            events += change
        }
    }

    private fun envWithChannel(channel: RecordingChannel, data: MutableDataObject = MutableDataObject()): Env {
        val rooted = MutableDataObject(frozen = false, channel = channel)
        // 复用传入对象的内容，保证 data 根带 channel
        for (key in data.keys()) rooted.setMember(key, data.member(key))
        return env(data = rooted)
    }

    @Test
    fun defineDoesNotEmitChange() {
        val channel = RecordingChannel()
        val env = envWithChannel(channel)
        env.define("item", "x")
        env.child().define("index", 0L)
        assertTrue(channel.events.isEmpty(), "建绑定不是改数据，不该发事件")
    }

    @Test
    fun assignEmitsChange() {
        val channel = RecordingChannel()
        val env = envWithChannel(channel, dataObjectOf("name" to "张三"))

        assertTrue(env.assign("name", "李四", WriteSource.UserBinding))
        assertEquals("name", channel.events.single().path)
    }

    @Test
    fun assignToConfigThrows() {
        val env = env(config = DataObject.frozen(mapOf("title" to "x")))
        assertFailsWith<FrozenWriteException> {
            env.assign("config.title", "y", WriteSource.Script)
        }
    }

    @Test
    fun relativeWriteFallsBackToData() {
        val channel = RecordingChannel()
        val env = envWithChannel(channel)
        assertTrue(env.assign("nickname", "阿四", WriteSource.Script))
        assertEquals("阿四", env.read("data.nickname"))
    }

    @Test
    fun referenceWriteUsesSlotOrigin() {
        val channel = RecordingChannel()
        val data = dataObjectOf("user" to mapOf("name" to "张三"))
        val env = envWithChannel(channel, data)

        env.define("u", env.read("data.user")!!, origin = Path.absolute("data", "user"))
        assertTrue(env.assign("u.name", "李四", WriteSource.Script))

        assertEquals("user.name", channel.events.single().path, "引用写入也要还原成绝对路径（E-2）")
        assertEquals("李四", env.read("data.user.name"))
    }

    @Test
    fun writeToBareLocalIsRejected() {
        val channel = RecordingChannel()
        val env = envWithChannel(channel)
        env.define("local", 1L)
        assertFalse(env.assign("local", 2L, WriteSource.Script), "没有 origin 的局部标量只能走 Slot.setValue")
        assertTrue(channel.events.isEmpty())
    }

    @Test
    fun assignToUndefinedIntermediateFails() {
        val env = env()
        assertFalse(env.assign("a.b.c", 1L, WriteSource.Program))
        assertEquals(Absent, env.read("data.a"))
    }

    @Test
    fun slotValueIsReactiveAndNormalized() {
        val env = env()
        val slot = env.define("n", 1)
        assertEquals(1L, slot.value, "define 也走 normalize")
        slot.setValue(2)
        assertEquals(2L, slot.value)
    }

    @Test
    fun slotKeepsStateIdentity() {
        val env = env()
        val first = env.define("item", "a")
        val second = env.define("item", "b")
        assertSame(first, second, "同层重复 define 必须原地改值，否则订阅者全断")
        assertEquals("b", first.value)
    }

    @Test
    fun schemaDerivesCapabilitiesFromObjects() {
        val schema = EnvSchema.from(
            mapOf(
                "data" to dataObjectOf(),
                "config" to DataObject.frozen(mapOf("k" to 1)),
                "action" to DataObject.frozen(emptyMap()),
                "http" to DataObject.frozen(mapOf("get" to Fn.noArg { "x" })),
            ),
        )
        assertEquals(GlobalKind.Writable, schema.kindOf("data"))
        assertEquals(GlobalKind.ReadOnly, schema.kindOf("config"))
        assertEquals(GlobalKind.Callable, schema.kindOf("action"), "空的 action 仍是函数容器")
        assertEquals(GlobalKind.Callable, schema.kindOf("http"))
    }

    @Test
    fun fallbackMappingIsConfigurable() {
        val schema = EnvSchema(
            globals = mapOf("app" to GlobalKind.Writable),
            fallback = mapOf(SlotKind.Data to "app"),
        )
        val env = Env.root(schema, mapOf("app" to dataObjectOf("x" to 1)))
        assertEquals(1L, env.read("x"))
    }
}
