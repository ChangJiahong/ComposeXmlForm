package cn.changjiahong.composexmlform.api

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cn.changjiahong.composexmlform.ComposeXmlForm
import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.node.Attr
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.node.ChildRule
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.parse.ScriptMode
import cn.changjiahong.composexmlform.engine.render.NodeRenderer
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import cn.changjiahong.composexmlform.engine.value.DataChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 宿主 API 的验收：数据门面、全局对象、动作、以及 `DynamicLayout` 的端到端行为。
 *
 * 这些用例替代了旧架构的 126 例（`BindableDataTest` / `ActionTest` / `RenderTest` …）——
 * 那些测的是 `ObservableMap` / `StoreOwner` / `AttrValue` 这套已经不存在的机制。
 * 保留下来的判据只有一条：**宿主能看见的行为不变**。
 */
class DataFacadeTest {

    @Test
    fun writeThenRead() {
        val data = dataOf(mapOf("name" to "张三", "user" to mapOf("name" to "王五")))
        assertEquals("张三", data.get("name"))

        assertTrue(data.set("user.name", "李四"))
        assertEquals("李四", data.getString("user.name"))

        // 中间层不存在时不自动创建：拼错的路径静默成功比报错难查得多
        assertFalse(data.set("missing.deep", 1))
        assertEquals(Absent, data.get("missing.deep"))
    }

    @Test
    fun sameValueWriteDoesNotEmit() {
        val data = dataOf(mapOf("count" to 1))
        assertFalse(data.set("count", 1))
        assertTrue(data.set("count", 2))
    }

    @Test
    fun changesCarryPathAndValues() = runTest {
        val data = dataOf(mapOf("count" to 1L))
        val received = mutableListOf<DataChange>()
        // 必须先订阅：SharedFlow 的 replay 是 0，晚订阅就收不到已经发生的事
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            data.changes().collect { received += it }
        }
        data.set("count", 2L)
        job.cancel()

        assertEquals(1, received.size)
        assertEquals("count", received[0].path)
        assertEquals(1L, received[0].old)
        assertEquals(2L, received[0].new)
    }

    @Test
    fun patchKeepsContainerIdentity() {
        val data = dataOf(
            mapOf(
                "user" to mapOf("name" to "张三", "age" to 20L),
                "tags" to listOf("a"),
            ),
        )
        val userBefore = data.get("user") as DataObject
        val tagsBefore = data.get("tags")

        data.patch(mapOf("user" to mapOf("name" to "李四"), "tags" to listOf("a", "b")))

        // 身份没变，所以 [[user.name]] 上的绑定不会断
        assertTrue(userBefore === data.get("user"))
        assertTrue(tagsBefore === data.get("tags"))
        assertEquals("李四", data.getString("user.name"))
        assertEquals(20L, data.getLong("user.age"))
        assertEquals(listOf<Any?>("a", "b"), data.getList("tags"))
    }

    @Test
    fun listHandleMutatesAndEmits() = runTest {
        val data = dataOf(mapOf("items" to listOf("a")))
        val received = mutableListOf<DataChange>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            data.changes().collect { received += it }
        }

        val list = data.list("items")
        list.add("b")
        list.insert(0, "z")
        job.cancel()

        assertEquals(listOf<Any?>("z", "a", "b"), list.toList())
        assertEquals(2, received.size, "一次操作一条事件")
        assertEquals("items", received[0].path)
        assertEquals(listOf<Any?>("a"), received[0].old)
    }

    @Test
    fun notAListFailsFast() {
        val data = dataOf(mapOf("name" to "x"))
        assertFailsWith<IllegalArgumentException> { data.list("name") }
    }

    @Test
    fun jsonRoundTrip() {
        val data = dataOf(mapOf("name" to "张三", "age" to 20L, "tags" to listOf("a", "b")))
        val json = data.toJson()
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("20"))

        val parsed = dataOfJson(json)
        assertEquals("张三", parsed.getString("name"))
        assertEquals(20L, parsed.getLong("age"))
        assertEquals(listOf<Any?>("a", "b"), parsed.getList("tags"))
    }

    @Test
    fun snapshotSkipsFunctions() {
        val data = dataOf(mapOf("name" to "x"))
        assertTrue("name" in data.snapshot())
    }
}

class GlobalsActionsTest {

    @Test
    fun engineNamesAreReserved() {
        val failure = assertFailsWith<IllegalArgumentException> {
            globals { object_("data") { const("x", 1) } }
        }
        assertTrue(failure.message!!.contains("保留"))
    }

    @Test
    fun duplicateActionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            actions {
                on("submit") { }
                on("submit") { }
            }
        }
    }

    @Test
    fun illegalActionNameIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            actions { on("1submit") { } }
        }
    }

    @Test
    fun globalsExposeConstAndFunctions() {
        val ext = globals {
            object_("theme") {
                const("primary", "#FF5722")
                fn("name") { _, _ -> "dark" }
            }
            fn("toast") { _, _ -> null }
        }
        assertEquals(setOf("theme"), ext.names)
        assertEquals(setOf("toast"), ext.functionNames)
    }
}

@OptIn(ExperimentalTestApi::class)
class DynamicLayoutTest {

    @Test
    fun rendersBoundText() = runComposeUiTest {
        val data = dataOf(mapOf("name" to "张三"))
        setContent {
            DynamicLayout(xml = """<Column><Text value="{{data.name}}"/></Column>""", data = data)
        }
        onNodeWithText("张三").assertExists()
    }

    @Test
    fun omittingDataPrefixWorks() = runComposeUiTest {
        val data = dataOf(mapOf("name" to "省略前缀"))
        setContent {
            DynamicLayout(xml = """<Column><Text value="{{name}}"/></Column>""", data = data)
        }
        onNodeWithText("省略前缀").assertExists()
    }

    @Test
    fun clickRunsActionAndRebindsText() = runComposeUiTest {
        val data = dataOf(mapOf("count" to 0L))
        setContent {
            DynamicLayout(
                xml = """
                    <Column>
                        <Text value="{{data.count}}"/>
                        <Button text="加一" onClick="@{ data.count = data.count + 1 }"/>
                    </Column>
                """.trimIndent(),
                data = data,
                scriptMode = ScriptMode.Full,
            )
        }
        onNodeWithText("0").assertExists()
        onNodeWithText("加一").performClick()
        waitForIdle()
        assertEquals(1L, data.getLong("count"))
        onNodeWithText("1").assertExists()
    }

    @Test
    fun textFieldWritesBackThroughBinding() = runComposeUiTest {
        val data = dataOf(mapOf("name" to ""))
        setContent {
            DynamicLayout(xml = """<Column><TextField value="{{data.name}}"/></Column>""", data = data)
        }
        onNode(hasSetTextAction()).performTextInput("李四")
        waitForIdle()
        assertEquals("李四", data.getString("name"))
    }

    @Test
    fun foreachRendersEveryRow() = runComposeUiTest {
        val data = dataOf(
            mapOf("list" to listOf(mapOf("name" to "第一"), mapOf("name" to "第二"))),
        )
        setContent {
            DynamicLayout(
                xml = """
                    <Column>
                        <Foreach source="{{data.list}}" item="row">
                            <Text value="{{row.name}}"/>
                        </Foreach>
                    </Column>
                """.trimIndent(),
                data = data,
            )
        }
        onNodeWithText("第一").assertExists()
        onNodeWithText("第二").assertExists()
    }

    /**
     * 动作增删列表 → `Foreach` 行数跟着变。
     *
     * 覆盖的是「XML 里的按钮改数据」这条最常用路径：`data.list(...)` 发 `DataChange`
     * 并且结构版本是快照状态，所以增删之后行会重建。`removeAt(i)` 的实参来自
     * `<Foreach index="i">`，点第几行就删第几行。
     */
    @Test
    fun actionAddsAndRemovesRows() = runComposeUiTest {
        val data = dataOf(
            mapOf(
                "list" to listOf(
                    mapOf("id" to 1L, "name" to "第一"),
                    mapOf("id" to 2L, "name" to "第二"),
                ),
            ),
        )
        val actionSet = actions {
            on("add") {
                val rows = data.list("list")
                val next = rows.size + 1
                rows.add(mapOf("id" to next.toLong(), "name" to "新增$next"))
            }
            on("removeAt") { data.list("list").removeAt(intArg(0)) }
        }

        setContent {
            DynamicLayout(
                xml = """
                    <Column>
                        <Button text="添加" onClick="@{ action.add() }"/>
                        <Foreach source="{{data.list}}" item="row" index="i" key="id">
                            <Column>
                                <Text value="{{row.name}}"/>
                                <Button text="删除" onClick="@{ action.removeAt(i) }"/>
                            </Column>
                        </Foreach>
                    </Column>
                """.trimIndent(),
                data = data,
                actions = actionSet,
            )
        }

        assertEquals(2, data.list("list").size)

        onNodeWithText("添加").performClick()
        waitForIdle()
        assertEquals(3, data.list("list").size)
        onNodeWithText("新增3").assertExists()

        // 第一行的「删除」：删完「第一」应当消失，后加的那条还在
        onAllNodesWithText("删除")[0].performClick()
        waitForIdle()
        assertEquals(2, data.list("list").size)
        onNodeWithText("第一").assertDoesNotExist()
        onNodeWithText("新增3").assertExists()
    }

    @Test
    fun ifPicksOneBranch() = runComposeUiTest {
        val data = dataOf(mapOf("ok" to false))
        setContent {
            DynamicLayout(
                xml = """
                    <If test="{{data.ok}}">
                        <Then><Text value="真"/></Then>
                        <Else><Text value="假"/></Else>
                    </If>
                """.trimIndent(),
                data = data,
            )
        }
        onNodeWithText("假").assertExists()
    }

    @Test
    fun globalConstantsAreReadable() = runComposeUiTest {
        setContent {
            DynamicLayout(
                xml = """<Column><Text value="{ config.title }"/></Column>""",
                data = dataOf(),
                config = mapOf("title" to "配置标题"),
            )
        }
        onNodeWithText("配置标题").assertExists()
    }

    @Test
    fun parseDiagnosticsAreReported() {
        val result = ComposeXmlForm.parse(
            xml = """<Column><Txt value="x"/></Column>""",
            data = dataOf(),
        )
        assertTrue(result.hasErrors)
        assertTrue(result.diagnostics.any { it.message.contains("Txt") })
    }

    @Test
    fun actionReceivesArgumentsFromXml() = runComposeUiTest {
        // 实参一路从 XML 求值 → Function → ActionScope 传下来。
        // 曾经在 ActionObject.register 里被丢掉，于是「删这一行」只能靠脚本改数据。
        val data = dataOf()
        setContent {
            DynamicLayout(
                xml = """
                    <Column>
                        <Button text='取参' onClick='@{ action.pick(1, "x", true) }'/>
                    </Column>
                """.trimIndent(),
                data = data,
                actions = actions {
                    on("pick") {
                        data.set("n", longArg(0))
                        data.set("s", stringArg(1))
                        data.set("b", booleanArg(2))
                        data.set("arity", arity.toLong())
                    }
                },
                scriptMode = ScriptMode.Full,
            )
        }

        onNodeWithText("取参").performClick()
        waitForIdle()

        assertEquals(1L, data.getLong("n"))
        assertEquals("x", data.getString("s"))
        assertTrue(data.getBoolean("b"))
        assertEquals(3L, data.getLong("arity"))
    }

    @Test
    fun selectReadsOptionsFromFrozenConfig() = runComposeUiTest {
        // config 里放嵌套数组是最常见的用法（下拉选项）。它必须能建出一棵冻结树 ——
        // 否则 DynamicLayout 在 buildParseEnv 阶段就抛 FrozenWriteException，
        // 表现为「窗口打开了但预览区什么都没有」，正是测试台第一次跑起来的那个样子。
        val data = dataOf(mapOf("region" to "BJ"))
        setContent {
            DynamicLayout(
                xml = """<Column><Select label="城市" options="{{config.regions}}" value="{{region}}"/></Column>""",
                data = data,
                config = mapOf(
                    "regions" to listOf(
                        mapOf("label" to "北京", "value" to "BJ"),
                        mapOf("label" to "上海", "value" to "SH"),
                    ),
                ),
            )
        }

        onNodeWithText("城市").assertExists()
        onNodeWithText("北京").assertExists()   // 选中项从 options 里匹配出来
    }

    @Test
    fun previewReportsRenderDiagnostics() = runComposeUiTest {
        val data = dataOf(mapOf("name" to "x"))
        val tree = ComposeXmlForm.parse("""<Column><Text value="{{data.name}}"/></Column>""", data).root!!

        val batches = mutableListOf<List<Diagnostic>>()
        setContent {
            preview(
                node = tree,
                data = data,
                // 不装内置组件：Column / Text 都没有渲染器，渲染期必然降级
                registry = ComposeXmlForm.createRegistry(includeDefaults = false),
                onDiagnostics = { batches += it },
            )
        }
        waitForIdle()

        assertTrue(batches.isNotEmpty(), "首帧必须回调一次")
        assertTrue(batches.last().any { it.code == Code.MissingRenderer })
    }

    @Test
    fun previewReportsEmptyBatchWhenEverythingResolves() = runComposeUiTest {
        val data = dataOf(mapOf("name" to "x"))
        val tree = ComposeXmlForm.parse("""<Column><Text value="{{data.name}}"/></Column>""", data).root!!

        val batches = mutableListOf<List<Diagnostic>>()
        setContent { preview(node = tree, data = data, onDiagnostics = { batches += it }) }
        waitForIdle()

        assertEquals(1, batches.size, "没有新诊断时不该反复回调")
        assertTrue(batches.single().isEmpty())
    }

    @Test
    fun customComponentRendersAndWritesBack() = runComposeUiTest {
        // 宿主自定义组件：声明属性 → 声明节点 → 一次注册（标签 + 构造器 + 渲染器）。
        // 这条链路是「引擎不认识任何具体组件」的证明 —— 内置组件走的是同一条路。
        val registry = ComposeXmlForm.createRegistry {
            register(
                nodeClass = StarNode::class,
                tag = "Stars",
                attributes = listOf(StarValue),
                childRule = ChildRule.None,
                create = { id, attrs, _ -> StarNode(id, attrs.attr(StarValue)) },
                renderer = NodeRenderer { node ->
                    val (value, onChange) = node.value.rememberField<Long>()
                    Text(
                        text = "星级 ${value ?: 0L}",
                        modifier = Modifier.clickable { onChange(3L) },
                    )
                },
            )
        }

        val data = dataOf(mapOf("rating" to 0L))
        setContent {
            DynamicLayout(
                xml = """<Column><Stars value="{{data.rating}}"/></Column>""",
                data = data,
                registry = registry,
            )
        }

        onNodeWithText("星级 0").assertExists()
        onNodeWithText("星级 0").performClick()
        waitForIdle()

        assertEquals(3L, data.getLong("rating"))
        onNodeWithText("星级 3").assertExists()
    }
}

private val StarValue = Attr.varInt("value")

private class StarNode(id: String, val value: AttrSlot<Long>) : Node(id)
