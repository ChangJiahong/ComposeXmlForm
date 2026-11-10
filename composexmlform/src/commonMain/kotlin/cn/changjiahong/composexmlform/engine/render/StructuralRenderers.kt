package cn.changjiahong.composexmlform.engine.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.node.ElseNode
import cn.changjiahong.composexmlform.engine.node.ForeachNode
import cn.changjiahong.composexmlform.engine.node.IfNode
import cn.changjiahong.composexmlform.engine.node.ThenNode
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Path
import cn.changjiahong.composexmlform.engine.value.display
import cn.changjiahong.composexmlform.engine.value.kindOf

/**
 * 四个结构节点的渲染器。
 *
 * 它们没有任何像素：`If` 决定画哪一支，`Foreach` 决定用哪套局部变量画几遍，
 * `Then` / `Else` 只是把子节点转出去。真正的组件渲染器由宿主注册。
 */
object StructuralRenderers {

    /** `<If test="…">`：只画一支，另一支**根本不组合** —— 所以它的状态不会留下来（R-6）。 */
    val If: NodeRenderer<IfNode> = NodeRenderer { node -> RenderIf(node) }

    val Then: NodeRenderer<ThenNode> = NodeRenderer { node -> RenderChildren(node) }

    val Else: NodeRenderer<ElseNode> = NodeRenderer { node -> RenderChildren(node) }

    /** `<Foreach>`：按数组逐行渲染，每行一层 env。 */
    val Foreach: NodeRenderer<ForeachNode> = NodeRenderer { node -> RenderForeach(node) }

    /** 四个渲染器。与 `StructuralTypes.All` 配套使用。 */
    val All: Renderers = Renderers.of(
        IfNode::class to If,
        ThenNode::class to Then,
        ElseNode::class to Else,
        ForeachNode::class to Foreach,
    )
}

@Composable
private fun RenderIf(node: IfNode) {
    val ctx = rememberEvalContext()
    val sink = LocalDiagnostics.current

    val condition = node.test.renderValue(ctx, sink) ?: false
    val branch = if (condition) node.thenBranch else node.elseBranch
    if (branch != null) RenderNode(branch)
}

@Composable
private fun RenderForeach(node: ForeachNode) {
    val env = LocalEnv.current
    val ctx = rememberEvalContext(env)
    val sink = LocalDiagnostics.current

    val source = node.source.renderValue(ctx, sink)
    val list = source as? DataArray
    if (list == null) {
        // `Absent` / `null` 是「还没数据」，安静地渲染成空；别的类型是配置错误，要报出来
        if (source != null && source !== Absent) {
            sink.report(
                Diagnostic(
                    severity = Severity.Warning,
                    code = Code.AttributeFallback,
                    message = "『source』需要数组，收到 ${kindOf(source).display}；已按空列表渲染",
                    span = node.source.site.span,
                    tag = node.source.site.tag,
                    attribute = node.source.site.attribute,
                ),
            )
        }
        return
    }

    val itemName = node.item.renderValue(ctx, sink).orEmpty()
    val indexName = node.index.renderValue(ctx, sink).orEmpty()
    val keyBy = node.keyBy.renderValue(ctx, sink)?.takeIf { it.isNotEmpty() }
    val origin = node.source.boundPath

    val count = list.size
    if (count == 0) return

    val usedKeys = HashSet<Any>(count)
    for (index in 0 until count) {
        val rowKey = rowKeyOf(list, index, keyBy, usedKeys) ?: index
        key(rowKey) {
            ForeachRow(
                node = node,
                parent = env,
                list = list,
                index = index,
                itemName = itemName,
                indexName = indexName,
                origin = origin,
            )
        }
    }
}

/**
 * 一行。
 *
 * 三层身份一起保证「元素换了但行没换」时状态不丢：
 *
 * ```
 * key(rowKey)                      ← 行身份
 *   remember(parent) { child() }   ← env 只在行首次组合时建一次
 *     list.get(index)              ← 元素值在这里读 → 只订阅**这一个元素**
 * ```
 *
 * 行身份默认是**元素本身**（见 [rowKeyOf]），所以「这一条被删了」等价于
 * 「这一组不存在了」，Compose 会直接销毁它，不需要靠任何失效传播去纠正。
 * 元素换了实例 → 新的一组 → 内层状态自然重建；元素里的**字段**变了则实例不变，
 * 只有读到那个字段的节点重组 —— 这正是 `@{}` 捕获不失效的原因（R-2）。
 *
 * 反过来，旧实现把 `element` 放进 `remember` 的 key，元素一换 env 就重建，
 * 行内事件处理器捕获的上下文随之失效，动画期间点击会拿到旧值。
 */
@Composable
private fun ForeachRow(
    node: ForeachNode,
    parent: Env,
    list: DataArray,
    index: Int,
    itemName: String,
    indexName: String,
    origin: Path?,
) {
    val element = list.get(index)
    val itemOrigin = origin?.child(index.toString())

    val rowEnv = remember(parent) { parent.child() }

    if (itemName.isNotEmpty()) {
        rowEnv.define(itemName, element, origin = itemOrigin)
    }
    if (indexName.isNotEmpty()) {
        rowEnv.define(indexName, index.toLong(), mutable = false)
    }

    RenderChildren(node, rowEnv)
}


/**
 * 行 key。
 *
 * | 写了 `key="id"` | 没写 |
 * | --- | --- |
 * | 用元素里那个字段 | 用**元素本身** |
 *
 * 默认不用下标，是因为下标会随增删错位：删掉第 0 条之后，「下标 0 那一行」换成了
 * 另一条数据，但它的参数一个都没变 —— Compose 会跳过这一组，而它订阅的是
 * 「被删掉的那个元素的状态」，那个状态已经不在数组里，永远不会再变。
 * 表现就是「数据删了、界面还是旧的那行」，而且靠再等一帧也修不回来。
 * 元素实例才是这一行的身份，用它能直接得出「这一组不存在了」。
 *
 * 拿不到可用 key 时（元素是 `Absent` / `null`、对象里没有那个字段、或者两个元素相等）
 * 退回下标 —— 「有 key 用 key，没有就先按位置」比「整列表崩掉」好得多；
 * 重复 key 会让 Compose 抛「Key was already used」，所以用 `used` 记着，撞了就退。
 */
private fun rowKeyOf(
    list: DataArray,
    index: Int,
    keyBy: String?,
    used: MutableSet<Any>,
): Any? {
    val element = list.get(index)
    val raw = if (keyBy == null) element else (element as? DataObject)?.member(keyBy)

    if (raw == null || raw === Absent) return index
    if (!used.add(raw)) return index
    return raw
}
