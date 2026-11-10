package cn.changjiahong.composexmlform.engine.value

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 容器行为：订阅粒度、结构版本、路径写入与变更事件。
 *
 * 前两组是**硬门槛**（PLAN §6）：
 * 订阅粒度退化成整表 state，`Foreach` 的元素级更新全废；
 * 结构版本缺失，则是「写了数据界面不动」的诡异 bug。
 */
class DataObjectTest {

    /** 记录读到的 state，用来断言「读谁就只订阅谁」。 */
    private class ReadProbe {
        val reads = mutableListOf<Any>()

        fun observe(block: () -> Unit) {
            Snapshot.observe(readObserver = { reads += it }) { block() }
        }
    }

    // ── 订阅粒度（硬门槛）────────────────────────────────────

    @Test
    fun readingOneKeySubscribesOnlyThatKey() {
        val obj = dataObjectOf("a" to 1, "b" to 2)
        val probe = ReadProbe()
        probe.observe { obj.member("a") }

        assertEquals(1, probe.reads.size, "读一个字段只能产生一次 state 读（否则退化成整表订阅）")

        val stateA = probe.reads.single() as State<*>
        assertEquals(1L, stateA.value)

        obj.setMember("b", 20L)
        assertEquals(1L, stateA.value, "写 b 不该动 a 的 state")
    }

    @Test
    fun writingExistingKeyDoesNotBumpStructure() {
        val obj = dataObjectOf("a" to 1)
        val probe = ReadProbe()
        probe.observe { obj.member("missing") }
        val structure = probe.reads.single() as State<*>
        val before = structure.value as Long

        obj.setMember("a", 2L)
        assertEquals(before, structure.value, "改已有字段不属于结构变化")
    }

    // ── 结构版本（R-1 硬门槛）────────────────────────────────

    @Test
    fun readingMissingFieldSubscribesToStructureChange() {
        val obj = dataObjectOf()
        val probe = ReadProbe()
        probe.observe { assertEquals(Absent, obj.member("c")) }

        assertTrue(probe.reads.isNotEmpty(), "读缺失字段必须订阅某个 state，否则写进来也不会更新")
        val structure = probe.reads.single() as State<*>
        val before = structure.value as Long

        obj.setMember("c", 1L)
        assertTrue((structure.value as Long) > before, "新增字段必须让结构版本变化")
        assertEquals(1L, obj.member("c"))
    }

    @Test
    fun removingKeyBumpsStructure() {
        val obj = dataObjectOf("a" to 1)
        val probe = ReadProbe()
        probe.observe { obj.member("zzz") }
        val structure = probe.reads.single() as State<*>
        val before = structure.value as Long

        assertTrue(obj.removeMember("a"))
        assertFalse(obj.removeMember("a"), "删不存在的字段返回 false")
        assertTrue((structure.value as Long) > before)
    }

    @Test
    fun keysSubscribesToStructure() {
        val obj = dataObjectOf("a" to 1)
        val probe = ReadProbe()
        probe.observe { obj.keys() }
        assertTrue(probe.reads.isNotEmpty())
        val structure = probe.reads.first() as State<*>
        val before = structure.value as Long
        obj.setMember("b", 2L)
        assertTrue((structure.value as Long) > before)
    }

    // ── 数组 ──────────────────────────────────────────────────

    @Test
    fun arrayElementSubscriptionIsPerElement() {
        val array = dataArrayOf(1, 2)
        val probe = ReadProbe()
        probe.observe { array.get(0) }
        assertEquals(1, probe.reads.size)
        val first = probe.reads.single() as State<*>

        array.set(1, 20L)
        assertEquals(1L, first.value)
    }

    @Test
    fun appendingNotifiesOutOfRangeReader() {
        val array = dataArrayOf(1)
        val probe = ReadProbe()
        probe.observe { assertEquals(Absent, array.get(5)) }
        val structure = probe.reads.single() as State<*>
        val before = structure.value as Long

        array.append(2L)
        assertTrue((structure.value as Long) > before)
        assertEquals(2L, array.get(1))
    }

    @Test
    fun arrayMutationsRespectBounds() {
        val array = dataArrayOf(1, 3)
        assertTrue(array.insert(1, 2L))
        assertEquals(listOf<Value>(1L, 2L, 3L), array.elements)
        assertEquals(2L, array.removeAt(1))
        assertFalse(array.set(9, 0L))
        assertEquals(Absent, array.removeAt(9))
        assertEquals(1, array.indexOf(3L))
    }

    // ── 路径写入与变更事件 ───────────────────────────────────

    private class RecordingChannel : ChangeChannel {
        val events = mutableListOf<DataChange>()
        override fun emit(change: DataChange) {
            events += change
        }
    }

    @Test
    fun assignEmitsAbsolutePathRelativeToRoot() {
        val channel = RecordingChannel()
        val data = MutableDataObject(frozen = false, channel = channel)
        data.setMember("user", mapOf("name" to "张三"))

        assertTrue(data.assign("user.name", "李四", WriteSource.UserBinding))

        assertEquals(1, channel.events.size)
        val event = channel.events.single()
        assertEquals("user.name", event.path)
        assertEquals("张三", event.old)
        assertEquals("李四", event.new)
        assertEquals(WriteSource.UserBinding, event.source)
    }

    @Test
    fun assignThroughArrayFormatsIndexPath() {
        val channel = RecordingChannel()
        val data = MutableDataObject(frozen = false, channel = channel)
        data.setMember("list", listOf(mapOf("id" to 1)))

        assertTrue(data.assign("list[0].id", 7L, WriteSource.Script))
        assertEquals("list[0].id", channel.events.single().path)
    }

    @Test
    fun assignCreatesTopLevelField() {
        val channel = RecordingChannel()
        val data = MutableDataObject(frozen = false, channel = channel)
        // 脚本 `x = 2` 在本层未声明时兜底成 data.x（K-7），就是这条路径
        assertTrue(data.assign("x", 2L, WriteSource.Script))
        assertEquals(2L, data.member("x"))
        assertEquals("x", channel.events.single().path)
    }

    @Test
    fun assignDoesNotCreateIntermediateObjects() {
        val channel = RecordingChannel()
        val data = MutableDataObject(frozen = false, channel = channel)

        assertFalse(data.assign("a.b.c", 1L, WriteSource.Program), "中间层不存在时不能静默创建")
        assertTrue(channel.events.isEmpty())
        assertEquals(Absent, data.member("a"))
    }

    @Test
    fun assignThroughHostObjectFails() {
        val data = dataObjectOf("host" to StringBuilder())
        assertFalse(data.assign("host.name", "x", WriteSource.Program), "HostObject 不可下钻（V-2）")
    }

    @Test
    fun nestedContainerDoesNotEmitByItself() {
        val data = dataObjectOf("user" to mapOf("name" to "张三"))
        val user = assertIs<DataObject>(data.member("user"))
        // 直接写嵌套对象：没有路径信息，无权发事件（这是设计约束，不是遗漏）
        user.setMember("name", "李四")
        assertEquals("李四", user.member("name"))
    }

    @Test
    fun frozenObjectRejectsAssign() {
        val config = DataObject.frozen(mapOf("k" to 1))
        kotlin.test.assertFailsWith<cn.changjiahong.composexmlform.engine.diag.FrozenWriteException> {
            config.assign("k", 2L, WriteSource.Program)
        }
    }

    /**
     * 冻结对象必须**建得出来**：`frozen` 只该拦之后的写，不该拦构造期的规范化。
     *
     * `DataArray.ofValues` 曾经走 `append`，于是 `config` 里只要有一个嵌套数组，
     * 建 config 的时候就抛 `FrozenWriteException` —— 而 `config` 里放数组是最常见的用法
     * （下拉选项、默认列表）。这个用例守的就是那条路。
     */
    @Test
    fun frozenObjectAcceptsNestedListAndObject() {
        val config = DataObject.frozen(
            mapOf(
                "regions" to listOf(mapOf("label" to "北京", "value" to "BJ")),
                "tags" to listOf("a", "b"),
                "nested" to mapOf("list" to listOf(1, 2)),
            ),
        )

        val regions = config.member("regions") as DataArray
        assertEquals(1, regions.size)
        assertEquals("BJ", (regions.get(0) as DataObject).member("value"))
        assertEquals(2, (config.member("tags") as DataArray).size)
        assertEquals(2L, ((config.member("nested") as DataObject).member("list") as DataArray).get(1))

        // 建得出来，但仍然是只读的
        kotlin.test.assertFailsWith<cn.changjiahong.composexmlform.engine.diag.FrozenWriteException> {
            regions.append("SH")
        }
        kotlin.test.assertFailsWith<cn.changjiahong.composexmlform.engine.diag.FrozenWriteException> {
            (config.member("nested") as DataObject).setMember("x", 1)
        }
    }

    @Test
    fun nullValueIsStoredNotAbsent() {
        val data = dataObjectOf()
        data.setMember("a", null)
        assertNull(data.member("a"))
        assertTrue(data.keys().contains("a"), "键存在，值为 null")
    }
}
