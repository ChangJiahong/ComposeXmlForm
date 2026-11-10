package cn.changjiahong.composexmlform.engine.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.node.ElseNode
import cn.changjiahong.composexmlform.engine.node.FnNode
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
 * 结构节点的渲染器。
 *
 * 它们没有任何像素：`If` 决定画哪一支，`Foreach` 决定用哪套局部变量画几遍，
 * `Fn` 给子树绑一个函数，`Then` / `Else` 只是把子节点转出去。真正的组件渲染器由宿主注册。
 */
object StructuralRenderers {

    /** `<If test="…">`：只画一支，另一支**根本不组合** —— 所以它的状态不会留下来（R-6）。 */
    val If: NodeRenderer<IfNode> = NodeRenderer { node -> RenderIf(node) }

    val Then: NodeRenderer<ThenNode> = NodeRenderer { node -> RenderChildren(node) }

    val Else: NodeRenderer<ElseNode> = NodeRenderer { node -> RenderChildren(node) }

    /** `<Foreach>`：按数组逐行渲染，每行一层 env。 */
    val Foreach: NodeRenderer<ForeachNode> = NodeRenderer { node -> RenderForeach(node) }

    /** `<Fn>`：把函数绑进新的一层 env，子树可见。 */
    val Fn: NodeRenderer<FnNode> = NodeRenderer { node -> RenderFn(node) }

    /** 五个渲染器。与 `StructuralTypes.All` 配套使用。 */
    val All: Renderers = Renderers.of(
        IfNode::class to If,
        ThenNode::class to Then,
        ElseNode::class to Else,
        ForeachNode::class to Foreach,
        FnNode::class to Fn,
    )
}

/**
 * `<Fn>`：定义函数 + 渲染子树。
 *
 * 函数体在**定义处**的 env 里求值（`ScriptFunction` 捕获 `ctx.env`），而名字绑在
 * **新的一层**（`WithEnv`）—— 于是函数体看不见自己，递归调用会解析失败而不是栈溢出。
 * 需要递归时把函数名写进 `data` 或交给宿主，这是刻意的边界。
 */
@Composable
private fun RenderFn(node: FnNode) {
    val ctx = rememberEvalContext()
    val sink = LocalDiagnostics.current

    val name = node.name.renderValue(ctx, sink)?.takeIf { it.isNotEmpty() } ?: return
    val body = node.body.renderValue(ctx, sink) ?: return

    WithEnv(mapOf(name to body)) { RenderChildren(node) }
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
 * key(rowKey)                      ← 行身份：rowKey 不变就不重建状态组
 *   remember(parent) { child() }   ← env 只在行首次组合时建一次
 *     list.get(index)              ← 元素值在这里读 → 只订阅**这一个元素**
 * ```
 *
 * 元素值变化只让这一行重组（`list.get` 是字段级订阅），行 env 不动 ——
 * 这正是 `@{}` 捕获不失效的原因（R-2）。
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

    val rowEnv = remember(parent) { parent.child() }

    if (itemName.isNotEmpty()) {
        rowEnv.define(itemName, element, origin = origin?.child(index.toString()))
    }
    if (indexName.isNotEmpty()) {
        rowEnv.define(indexName, index.toLong(), mutable = false)
    }

    RenderChildren(node, rowEnv)
}

/**
 * 行 key。
 *
 * 优先用 `key="id"` 指定的字段；拿不到（元素不是对象 / 没有这个字段 / 是 `Absent`）
 * 就退回下标 —— 「有 id 用 id，没有就先按位置」比「整列表退化成按位置」好得多。
 *
 * 重复 key 会让 Compose 抛「Key was already used」。数据里真出现两条同 id 时，
 * 退到下标并报一条诊断：界面还能用，问题也看得见。
 */
private fun rowKeyOf(
    list: DataArray,
    index: Int,
    keyBy: String?,
    used: MutableSet<Any>,
): Any? {
    if (keyBy == null) return index

    val element = list.get(index)
    val raw = (element as? DataObject)?.member(keyBy)
    val key = if (raw == null || raw === Absent) null else raw

    if (key == null) return index
    if (!used.add(key)) return index
    return key
}
