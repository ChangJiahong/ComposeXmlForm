package cn.changjiahong.composexmlform

import androidx.compose.runtime.snapshots.Snapshot
import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.api.DataChange
import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.core.DataException
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.TextFieldNode
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import cn.changjiahong.composexmlform.store.ObservableData
import cn.changjiahong.composexmlform.store.ObservableList
import cn.changjiahong.composexmlform.store.ObservableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * BindableData 门面契约。
 *
 * 这里覆盖的都是「漏掉就会变成偶发 bug」的点：JSON 归一化、写入去重、增量合并保住身份、
 * reset 后订阅仍然有效、observe 跟随嵌套对象整体替换。
 */
class BindableDataTest {

    // ---------------- 构造与 JSON ----------------

    @Test
    fun ofJsonNormalizesToNativeTypes() {
        val data = BindableData.ofJson(
            """{"name":"张三","age":30,"score":12.5,"vip":true,"none":null,
               "tags":["a","b"],"user":{"city":"BJ"}}""",
        )
        assertEquals("张三", data.get("name"))
        assertEquals(30L, data.get("age"))
        assertEquals(12.5, data.get("score"))
        assertEquals(true, data.get("vip"))
        assertNull(data.get("none"))
        assertEquals(listOf("a", "b"), data.getList("tags"))
        assertEquals(mapOf("city" to "BJ"), data.getMap("user"))

        assertEquals(30, data.getInt("age"))
        assertEquals("BJ", data.getString("user.city"))
        assertEquals("a", data.getString("tags[0]"))
    }

    @Test
    fun ofJsonRejectsNonObjectRoot() {
        assertFailsWith<DataException> { BindableData.ofJson("[1,2]") }
        assertFailsWith<DataException> { BindableData.ofJson("42") }
        assertFailsWith<DataException> { BindableData.ofJson("{bad json") }
    }

    @Test
    fun jsonRoundTripsThroughSnapshot() {
        val data = BindableData.of(mapOf("a" to 1, "b" to listOf(1, 2), "c" to mapOf("d" to true)))
        val json = data.getJson(pretty = false)
        assertEquals("""{"a":1,"b":[1,2],"c":{"d":true}}""", json)
        assertTrue(ObservableData.valuesEqual(data.snapshot(), BindableData.ofJson(json).snapshot()))
    }

    @Test
    fun dataHasNoConfigNamespace() {
        val data = BindableData.of(mapOf("username" to "张三"))
        // config 是解析期资源，运行时根本不存在这个变量：读它得到 null，而不是抛错
        assertNull(data.get("config"))
        assertNull(data.get("config.regions"))
    }

    // ---------------- 写入 ----------------

    @Test
    fun setReportsOnlyRealChanges() {
        val data = BindableData.of(mapOf("username" to "张三", "age" to 30))
        assertFalse(data.set("username", "张三"))
        assertTrue(data.set("username", "李四"))
        assertEquals("李四", data.getString("username"))
        // 数字按值比较：30 与 30L 视为没变
        assertFalse(data.set("age", 30L))
    }

    @Test
    fun setKeepsOriginalContainerForUnrelatedData() {
        val data = BindableData.of(mapOf("user" to mapOf("name" to "张三"), "region" to "BJ"))
        val user = data.get("user")
        data.set("user.name", "李四")
        assertSame(user, data.get("user"), "写嵌套字段不应替换对象容器，否则 Foreach / 焦点会失稳")
        assertEquals("李四", data.getString("user.name"))
    }

    @Test
    fun mergeKeepsContainerIdentityAndReportsChanges() {
        val data = BindableData.of(
            mapOf(
                "user" to mapOf("name" to "张三"),
                "contacts" to listOf(mapOf("id" to 1, "name" to "李雷")),
            ),
        )
        val userBefore = data.get("user") as ObservableMap
        val elementBefore = (data.get("contacts") as ObservableList).get(0) as ObservableMap

        val changed = data.merge(
            mapOf(
                "user" to mapOf("name" to "李四"),
                "contacts" to listOf(mapOf("id" to 1, "name" to "韩梅梅")),
            ),
        )

        assertTrue(changed)
        assertSame(userBefore, data.get("user"))
        assertSame(elementBefore, (data.get("contacts") as ObservableList).get(0))
        assertEquals("李四", data.getString("user.name"))
        assertEquals("韩梅梅", data.getString("contacts[0].name"))
        // 内容一致时再次合并应为无变化
        assertFalse(data.merge(mapOf("user" to mapOf("name" to "李四"))))
    }

    @Test
    fun mergeAppendsButNeverTruncatesList() {
        val data = BindableData.of(mapOf("list" to listOf(1, 2)))
        assertTrue(data.merge(mapOf("list" to listOf(1, 2, 3))))
        assertEquals(listOf(1, 2, 3), data.getList("list"))
        // 少于现有长度时保留多余元素：merge 不做删除
        assertFalse(data.merge(mapOf("list" to listOf(1, 2))))
        assertEquals(listOf(1, 2, 3), data.getList("list"))
    }

    @Test
    fun resetReplacesContentInPlace() {
        val data = BindableData.of(mapOf("a" to 1))
        val state = data.asState()
        assertEquals(1, state.value["a"])

        data.reset(mapOf("a" to 2, "b" to 3))

        // 若 reset 换了 map 实例，derivedStateOf 的依赖链会断开，这里会读到旧值
        assertEquals(2, state.value["a"])
        assertEquals(3, state.value["b"])
        assertEquals(setOf("a", "b"), data.snapshot().keys)
    }

    @Test
    fun asStateSeesNewTopLevelKey() {
        val data = BindableData.of(mapOf("a" to 1))
        val state = data.asState()
        assertEquals(setOf("a"), state.value.keys)
        data.set("b", 2)
        assertEquals(setOf("a", "b"), state.value.keys)
    }

    // ---------------- 列表 ----------------

    @Test
    fun appendAndRemoveKeepOtherElements() {
        val data = BindableData.of(mapOf("contacts" to listOf(mapOf("id" to 1, "name" to "李雷"))))
        val first = (data.get("contacts") as ObservableList).get(0)

        data.list("contacts").add(mapOf("id" to 2, "name" to "韩梅梅"))
        assertEquals(2, data.getList("contacts").size)
        assertSame(first, (data.get("contacts") as ObservableList).get(0))

        data.list("contacts").removeAt(1)
        assertEquals(1, data.getList("contacts").size)
        assertSame(first, (data.get("contacts") as ObservableList).get(0))
    }

    @Test
    fun listOperationsValidateBounds() {
        val data = BindableData.of(mapOf("contacts" to listOf(1)))
        assertFailsWith<DataException> { data.list("contacts").removeAt(5) }
        assertFailsWith<DataException> { data.list("username") }
    }

    // ---------------- 订阅 ----------------

    @Test
    fun observeFollowsNestedReplacement() = runBlocking {
        val data = BindableData.of(mapOf("user" to mapOf("name" to "A")))
        val waiter = async(Dispatchers.Unconfined) {
            withTimeout(5_000) { data.observe("user.name").first { it == "B" } }
        }
        yield()
        // 整体替换 user：只有「每次重新解析路径」的实现才能跟到新节点上
        data.set("user", mapOf("name" to "B"))
        assertEquals("B", waiter.await())
    }

    @Test
    fun observeEmitsPlainValues() = runBlocking {
        val data = BindableData.of(mapOf("user" to mapOf("name" to "A")))
        val waiter = async(Dispatchers.Unconfined) {
            withTimeout(5_000) { data.observe("user").first { it != null } }
        }
        yield()
        val value = waiter.await()
        assertTrue(value is Map<*, *>, "订阅者不应看到引擎内部的 ObservableMap")
        assertEquals(mapOf("name" to "A"), value)
    }

    @Test
    fun observeElementFollowsKeyAcrossReordering() = runBlocking {
        val data = BindableData.of(
            mapOf(
                "contacts" to listOf(
                    mapOf("id" to 1, "name" to "A"),
                    mapOf("id" to 2, "name" to "B"),
                ),
            ),
        )
        val waiter = async(Dispatchers.Unconfined) {
            withTimeout(5_000) {
                data.observeElement("contacts", "id", 2).first { it?.get("name") == "B2" }
            }
        }
        yield()
        // 删掉第 0 项：id=2 的元素从 contacts[1] 漂到 contacts[0]。
        // 若实现按「下标」订阅，这里就永久跟丢了。
        data.list("contacts").removeAt(0)
        data.set("contacts[0].name", "B2")
        assertEquals("B2", waiter.await()?.get("name"))
    }

    @Test
    fun observeElementEmitsFieldChanges() = runBlocking {
        val data = BindableData.of(mapOf("contacts" to listOf(mapOf("id" to 1, "name" to "A"))))
        val waiter = async(Dispatchers.Unconfined) {
            withTimeout(5_000) {
                data.observeElement("contacts", "id", 1).first { it?.get("name") == "B" }
            }
        }
        yield()
        data.set("contacts[0].name", "B")
        assertEquals("B", waiter.await()?.get("name"))
    }

    @Test
    fun observeElementEmitsNullWhenElementRemoved() = runBlocking {
        val data = BindableData.of(
            mapOf("contacts" to listOf(mapOf("id" to 1), mapOf("id" to 2))),
        )
        val waiter = async(Dispatchers.Unconfined) {
            // 订阅先建立（此时元素存在，首次 emit 非 null，不满足条件），元素被删后再收到 null
            withTimeout(5_000) { data.observeElement("contacts", "id", 2).first { it == null } }
        }
        yield()
        data.list("contacts").removeAt(1)
        assertNull(waiter.await())
    }

    @Test
    fun observeElementIgnoresOtherElements() = runBlocking {
        val data = BindableData.of(
            mapOf(
                "contacts" to listOf(
                    mapOf("id" to 1, "name" to "A"),
                    mapOf("id" to 2, "name" to "B"),
                ),
            ),
        )
        val flow = data.observeElement("contacts", "id", 2)
        assertEquals("B", flow.first()?.get("name"))

        // take(2)：只有目标元素被改动才会出现第二次 emit，否则超时返回 null
        val second = withTimeoutOrNull(500) {
            async(Dispatchers.Unconfined) { flow.take(2).toList() }.also {
                yield()
                data.set("contacts[0].name", "A2")
            }.await()
        }
        assertNull(second, "改其它元素不应触发目标元素的通知")
    }

    @Test
    fun observeElementValidatesListPath() {
        val data = BindableData.of(mapOf("username" to "张三"))
        assertFailsWith<DataException> { data.observeElement("username", "id", 1) }
        assertFailsWith<DataException> { data.observeElement("missing", "id", 1) }
    }

    @Test
    fun engineWriteBackIsVisibleThroughAsState() {
        // 「外部感知内部更新」的核心契约：TextField 的写回不经过 BindableData，
        // 而是走 StoreOwner → ObservableMap，门面必须看到同一份状态。
        val data = BindableData.of(mapOf("username" to "张三"))
        val runtime = DynamicLayoutRuntime(
            xml = """<Column><TextField value="{{username}}"/></Column>""",
            data = data,
            config = emptyMap(),
            actions = Actions.Empty,
            registry = ComposeXmlForm.createRegistry(),
        )
        val state = data.asState()
        assertEquals("张三", state.value["username"])

        runtime.rootOwner.write(ValuePath.parse("username"), "李四")

        // 等价于 Compose 帧循环那次 apply 通知；非 Compose 环境下需要显式补
        Snapshot.sendApplyNotifications()

        assertEquals("李四", data.getString("username"))
        assertEquals("李四", state.value["username"])
    }

    // ---------------- 变更契约（changes） ----------------

    @Test
    fun changesReportPathOldValueAndProgramSource() = runBlocking {
        val data = BindableData.of(mapOf("a" to 1))
        val waiter = async(Dispatchers.Unconfined) {
            withTimeout(5_000) { data.changes().take(2).toList() }
        }
        yield()
        data.set("a", 2)
        data.set("b", "x")

        val events = waiter.await()
        assertEquals("a", events[0].path)
        assertEquals(1, events[0].old)
        assertEquals(2, events[0].new)
        assertEquals(DataChange.Source.Program, events[0].source)
        assertEquals("b", events[1].path)
        assertNull(events[1].old)
        assertEquals("x", events[1].new)
    }

    @Test
    fun resetEmitsSingleAggregateEvent() = runBlocking {
        val data = BindableData.of(mapOf("a" to 1))
        // 若 reset 逐字段发事件，这里会凑够 2 条；只发一条时超时返回 null
        val waiter = async(Dispatchers.Unconfined) {
            withTimeoutOrNull(500) { data.changes().take(2).toList() }
        }
        yield()
        data.reset(mapOf("a" to 2, "b" to 3))

        assertNull(waiter.await(), "reset 应只发一条汇总事件")
    }

    @Test
    fun bindingWriteBackIsMarkedAsUserBinding() = runBlocking {
        val data = BindableData.of(mapOf("username" to "张三"))
        val runtime = DynamicLayoutRuntime(
            xml = """<Column><TextField value="{{username}}"/></Column>""",
            data = data,
            config = emptyMap(),
            actions = Actions.Empty,
            registry = ComposeXmlForm.createRegistry(),
        )
        val field = (runtime.layout.root as ColumnNode).children.single() as TextFieldNode

        val waiter = async(Dispatchers.Unconfined) {
            withTimeout(5_000) { data.changes().take(2).toList() }
        }
        yield()
        // 用户输入：走 VarAttr.write（渲染层路径）
        field.value!!.write(runtime.rootOwner, "李四")
        // 业务写入
        data.set("username", "王五")

        val events = waiter.await()
        assertEquals(DataChange.Source.UserBinding, events[0].source)
        assertEquals("张三", events[0].old)
        assertEquals("李四", events[0].new)
        assertEquals(DataChange.Source.Program, events[1].source)
    }

    @Test
    fun listOperationEmitsSingleEventWithListSnapshots() = runBlocking {
        val data = BindableData.of(mapOf("contacts" to listOf(mapOf("id" to 1))))
        val waiter = async(Dispatchers.Unconfined) {
            withTimeout(5_000) { data.changes().take(1).toList() }.single()
        }
        yield()
        data.list("contacts").add(mapOf("id" to 2))

        assertEquals("contacts", waiter.await().path)
        assertEquals(1, (waiter.await().old as List<*>).size)
        assertEquals(2, (waiter.await().new as List<*>).size)
    }
}
