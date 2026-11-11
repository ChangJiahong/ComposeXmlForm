package cn.changjiahong.composexmlform.engine.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.env.EnvSchema
import cn.changjiahong.composexmlform.engine.node.Attr
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.node.ChildRule
import cn.changjiahong.composexmlform.engine.node.ContainerNode
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.node.NodeType
import cn.changjiahong.composexmlform.engine.node.NodeTypes
import cn.changjiahong.composexmlform.engine.node.StructuralTypes
import cn.changjiahong.composexmlform.engine.parse.ScriptMode
import cn.changjiahong.composexmlform.engine.parse.parse
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.dataArrayOf
import cn.changjiahong.composexmlform.engine.value.dataObjectOf
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 渲染层测试。
 *
 * 计数方式与旧 `RenderTest` 一致：渲染器自增一个**普通** `MutableMap`。
 * 它刻意不是 `mutableStateOf` —— 计数本身不能触发重组，否则测的就是计数器。
 *
 * 这些用例是 R-1（结构版本）、R-2（行 env 稳定）、R-3（事件捕获）、
 * R-4（降级）、R-6（切分支不保留状态）的**硬门槛**：任何一条退化成「注释里的承诺」，
 * 这里就会红。
 */
@OptIn(ExperimentalTestApi::class)
class RenderTest {

    // ── 测试用的最小节点集 ────────────────────────────────────

    private class ProbeNode(
        id: String,
        val text: AttrSlot<String>,
        val onClick: AttrSlot<Function>,
    ) : Node(id)

    private class ColumnNode(id: String, children: List<Node>) : ContainerNode(id, children)

    private class GhostNode(id: String) : Node(id)

    private val Text = Attr.string("text")
    private val OnClick = Attr.func("onClick")

    private val types: NodeTypes = NodeTypes.of(
        NodeType("Probe", listOf(Text, OnClick), ChildRule.None) { id, attrs, _ ->
            ProbeNode(id, attrs.attr(Text), attrs.attr(OnClick))
        },
        NodeType("Column", childRule = ChildRule.Any) { id, _, children ->
            ColumnNode(id, children)
        },
        // 声明了类型但**故意不注册渲染器** —— 用来验证缺失降级
        NodeType("Ghost", childRule = ChildRule.None) { id, _, _ -> GhostNode(id) },
    ).plus(StructuralTypes.All)

    /**
     * 记录每次 `Render` 的执行次数、每个实例当前的 env 引用、以及它的 handler。
     *
     * 键必须带上**行标识**：`Foreach` 的每一行渲染的是**同一个 `Node` 实例**，
     * 只用 `node.id` 会让第 2 行覆盖第 1 行（那是测试自己的 bug，不是引擎的）。
     * 行标识取 `row.id`（`<Foreach>` 的 item 名字在测试里固定是 `row`）。
     */
    private class ProbeRenderer(
        val calls: MutableMap<String, Int> = mutableMapOf(),
        val envs: MutableMap<String, Env> = mutableMapOf(),
        val handlers: MutableMap<String, EventHandler> = mutableMapOf(),
    ) : NodeRenderer<ProbeNode> {

        @Composable
        override fun Render(node: ProbeNode) {
            val env = LocalEnv.current
            val key = instanceKey(node, env)
            calls[key] = (calls[key] ?: 0) + 1
            envs[key] = env

            val ctx = rememberEvalContext(env)
            val sink = LocalDiagnostics.current
            val text = node.text.renderValue(ctx, sink).orEmpty()
            val handler = node.onClick.buildHandler(ctx, env.captureLocals(), sink)
            if (handler != null) handlers[key] = handler

            BasicText(
                text = text,
                modifier = Modifier.clickable(enabled = handler != null) { handler?.invoke() },
            )
        }

        private fun instanceKey(node: ProbeNode, env: Env): String {
            val row = env.captureLocals()["row"] as? DataObject ?: return node.id
            val id = row.member("id")
            return if (id == null || id === Absent) node.id else "${node.id}#$id"
        }
    }

    private val probeRenderer = ProbeRenderer()

    private val renderers: Renderers = Renderers.of(
        ProbeNode::class to probeRenderer,
        ColumnNode::class to NodeRenderer<ColumnNode> { RenderChildren(it) },
    ).plus(StructuralRenderers.All)

    // ── 环境 ─────────────────────────────────────────────────

    private class RecordingSink : DiagnosticSink {
        val all = mutableListOf<Diagnostic>()
        override fun report(diagnostic: Diagnostic) {
            all += diagnostic
        }

        val codes: Set<Code> get() = all.mapTo(HashSet()) { it.code }
    }

    private val sink = RecordingSink()

    private fun scene(
        data: DataObject,
        action: DataObject = dataObjectOf(),
    ): Pair<Env, EnvSchema> {
        val objects = mapOf("data" to data, "action" to action)
        val schema = EnvSchema.from(objects)
        return Env.root(schema, objects) to schema
    }

    private fun root(xml: String, env: Env, schema: EnvSchema): Node {
        val result = parse(xml, schema, types, ScriptMode.Full)
        assertTrue(!result.hasErrors, "解析不该有错误：\n${result.format(xml)}")
        return result.root!!
    }

    @Composable
    private fun Host(env: Env, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalEnv provides env,
            LocalRenderers provides renderers,
            LocalDiagnostics provides sink,
        ) {
            content()
        }
    }

    // ── ① 绑定是活的 ─────────────────────────────────────────

    @Test
    fun textFollowsDataChange() = runComposeUiTest {
        val data = dataObjectOf("name" to "张三")
        val (env, schema) = scene(data)
        val node = root("""<Probe text="{{data.name}}"/>""", env, schema)

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()
        onNodeWithText("张三").assertExists()

        data.setMember("name", "李四")
        waitForIdle()
        onNodeWithText("李四").assertExists()
    }

    /**
     * R-1 端到端：字段**还不存在**时读过它，之后新增必须能看到。
     *
     * 没有结构版本 state 时，这条用例会红 —— 读缺失字段没有订阅任何东西，
     * 写入后界面不动，而现象是「数据明明改了」。
     */
    @Test
    fun writingAMissingFieldUpdatesTheBoundNode() = runComposeUiTest {
        val data = dataObjectOf()
        val (env, schema) = scene(data)
        val node = root("""<Probe text="{{data.nickname}}"/>""", env, schema)

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        data.setMember("nickname", "小明")
        waitForIdle()
        onNodeWithText("小明").assertExists()
    }

    /** 字段级订阅：改 `a` 不该让只读 `b` 的节点重组。 */
    @Test
    fun writingOneFieldOnlyRecomposesTheNodeThatReadsIt() = runComposeUiTest {
        val data = dataObjectOf("a" to "甲", "b" to "乙")
        val (env, schema) = scene(data)
        val node = root(
            """
            <Column>
              <Probe text="{{data.a}}"/>
              <Probe text="{{data.b}}"/>
            </Column>
            """.trimIndent(),
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        val readA = probeRenderer.calls.getValue("0.0")
        val readB = probeRenderer.calls.getValue("0.1")

        data.setMember("a", "丙")
        waitForIdle()

        assertTrue(probeRenderer.calls.getValue("0.0") > readA, "读了 a 的节点应当重组")
        assertTrue(probeRenderer.calls.getValue("0.1") == readB, "没读 a 的节点不该重组")
    }

    // ── ② Foreach ────────────────────────────────────────────

    /**
     * R-2 的核心：**元素对象被整体替换，行 env 不重建**。
     *
     * 旧实现把 `element` 放进 `remember` 的 key，元素一换 env 就重建 ——
     * 行内 `@{}` 捕获的上下文随之失效。这里用 `assertSame` 钉死。
     */
    @Test
    fun replacingAnElementKeepsTheRowEnv() = runComposeUiTest {
        val first = dataObjectOf("id" to 1L, "name" to "张三")
        val list = dataArrayOf(first)
        val data = dataObjectOf("list" to list)
        val (env, schema) = scene(data)
        val node = root(
            """<Foreach source="{{data.list}}" item="row" key="id"><Probe text="{{row.name}}"/></Foreach>""",
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()
        onNodeWithText("张三").assertExists()

        val rowEnv = probeRenderer.envs.getValue("0.0#1")

        list.set(0, dataObjectOf("id" to 1L, "name" to "李四"))
        waitForIdle()

        onNodeWithText("李四").assertExists()
        assertSame(rowEnv, probeRenderer.envs.getValue("0.0#1"), "行 env 不该因为元素替换而重建")
    }

    /** `index` 是响应式的：中间插入一条后，后面所有行的下标都跟着变。 */
    @Test
    fun indexFollowsInsertionsWhenRowsAreKeyed() = runComposeUiTest {
        val list = dataArrayOf(
            dataObjectOf("id" to 1L, "name" to "a"),
            dataObjectOf("id" to 2L, "name" to "b"),
        )
        val data = dataObjectOf("list" to list)
        val (env, schema) = scene(data)
        val node = root(
            """<Foreach source="{{data.list}}" item="row" index="i" key="id"><Probe text="{{i}}"/></Foreach>""",
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()
        onNodeWithText("0").assertExists()
        onNodeWithText("1").assertExists()

        list.insert(0, dataObjectOf("id" to 0L, "name" to "c"))
        waitForIdle()

        onNodeWithText("2").assertExists() // 原来的第二行现在是第 2 个
    }

    /**
     * R-3：`@{}` 捕获的是**渲染时的值快照**。
     *
     * 行被移除之后再触发同一个 handler，仍然读到那一行的 `row` ——
     * 这正是「快照」与「引用」的分界：引用会读到 `Absent`，快照还能拿到对象。
     */
    @Test
    fun handlerKeepsTheRowValueSnapshot() = runComposeUiTest {
        val received = mutableListOf<Any?>()
        val action = DataObject.frozen(
            mapOf(
                "record" to Function { _, args ->
                    received += args.firstOrNull()
                    null
                },
            ),
        )

        val list = dataArrayOf(
            dataObjectOf("id" to 1L, "name" to "张三"),
            dataObjectOf("id" to 2L, "name" to "李四"),
        )
        val data = dataObjectOf("list" to list)
        val (env, schema) = scene(data, action)
        val node = root(
            """
            <Foreach source="{{data.list}}" item="row" key="id">
              <Probe text="{{row.name}}" onClick="@{ action.record(row.name) }"/>
            </Foreach>
            """.trimIndent(),
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        val firstRowHandler = probeRenderer.handlers.getValue("0.0#1")
        firstRowHandler.invoke()
        assertTrue(received == listOf("张三"), "点击应当读到渲染时那一行的 row")

        // 移除第一行：它不再渲染，但 handler 仍握着那一行的快照
        list.removeAt(0)
        waitForIdle()

        firstRowHandler.invoke()
        assertTrue(received == listOf("张三", "张三"), "行移除后 handler 仍应读到旧快照")
    }

    // ── ③ If ─────────────────────────────────────────────────

    /** R-6：切分支之后，另一支**根本不再组合**。 */
    @Test
    fun ifRendersOnlyOneBranch() = runComposeUiTest {
        val data = dataObjectOf("ok" to true)
        val (env, schema) = scene(data)
        val node = root(
            """
            <If test="{{data.ok}}">
              <Then><Probe text="是"/></Then>
              <Else><Probe text="否"/></Else>
            </If>
            """.trimIndent(),
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()
        onNodeWithText("是").assertExists()

        data.setMember("ok", false)
        waitForIdle()
        onNodeWithText("否").assertExists()
    }

    // ── ④ 降级 ───────────────────────────────────────────────

    /** R-4：属性求值失败落到默认值，**并且留下诊断**。 */
    @Test
    fun failingAttributeFallsBackAndReports() = runComposeUiTest {
        val data = dataObjectOf("obj" to dataObjectOf("a" to 1L))
        val (env, schema) = scene(data)
        // text 是字符串槽位，绑到对象上必然收敛失败
        val node = root("""<Probe text="{{data.obj}}"/>""", env, schema)

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        assertTrue(
            Code.AttributeFallback in sink.codes,
            "应当上报 AttributeFallback，实际：${sink.all.map { it.message }}",
        )
    }

    /** 缺渲染器：画占位 + 上报，不抛。 */
    @Test
    fun missingRendererDegradesInsteadOfCrashing() = runComposeUiTest {
        val data = dataObjectOf()
        val (env, schema) = scene(data)
        val node = root("<Ghost/>", env, schema)

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        assertTrue(
            Code.MissingRenderer in sink.codes,
            "应当上报 MissingRenderer，实际：${sink.all.map { it.message }}",
        )
        onNodeWithText("未注册渲染器 <GhostNode>").assertExists()
    }

    // ── ⑤ Foreach 的边界 ─────────────────────────────────────

    /** 空列表只画零行，不报错。 */
    @Test
    fun emptyListRendersNothing() = runComposeUiTest {
        val data = dataObjectOf("list" to DataArray())
        val (env, schema) = scene(data)
        val node = root(
            """<Foreach source="{{data.list}}" item="row"><Probe text="{{row.name}}"/></Foreach>""",
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        assertTrue(probeRenderer.calls.isEmpty(), "空列表不该渲染任何行")
    }

    /** `source` 指向不存在的路径：渲染成空，**不报**（还没数据是正常状态）。 */
    @Test
    fun missingSourceRendersEmptyWithoutComplaint() = runComposeUiTest {
        val data = dataObjectOf()
        val (env, schema) = scene(data)
        val node = root(
            """<Foreach source="{{data.list}}" item="row"><Probe text="{{row.name}}"/></Foreach>""",
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        assertTrue(probeRenderer.calls.isEmpty())
        assertTrue(Code.AttributeFallback !in sink.codes, "「还没数据」不该报降级")
    }

    /** 列表追加元素：新行出现，已有行的 env 不动。 */
    @Test
    fun appendingAnElementAddsOneRow() = runComposeUiTest {
        val list = dataArrayOf(dataObjectOf("id" to 1L, "name" to "张三"))
        val data = dataObjectOf("list" to list)
        val (env, schema) = scene(data)
        val node = root(
            """<Foreach source="{{data.list}}" item="row" key="id"><Probe text="{{row.name}}"/></Foreach>""",
            env,
            schema,
        )

        setContent { Host(env) { RenderNode(node) } }
        waitForIdle()

        val firstRowEnv = probeRenderer.envs.getValue("0.0#1")

        list.append(dataObjectOf("id" to 2L, "name" to "李四"))
        waitForIdle()

        onNodeWithText("李四").assertExists()
        assertSame(firstRowEnv, probeRenderer.envs.getValue("0.0#1"), "追加不该重建已有行的 env")
    }
}
