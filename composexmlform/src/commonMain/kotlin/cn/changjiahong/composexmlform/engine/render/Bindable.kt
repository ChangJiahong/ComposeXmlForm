package cn.changjiahong.composexmlform.engine.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.eval.EvalContext
import cn.changjiahong.composexmlform.engine.node.Node

/**
 * 需要「按行绑定现场」的节点。
 *
 * 普通节点的属性是**节点自带**的（`AttrSlot` 里只有写法，取值靠 CompositionLocal），
 * 而带属性委托的节点希望 `node.value` 直接是值 —— 那就必须有一个**绑定过 env 的对象**。
 * 绑定的产物是一个新节点：`Foreach` 把同一个节点画 N 行，每行一份绑定，事件处理器捕获的
 * 也是自己那一行。
 *
 * 为什么不是「把 env 挂在节点上再复用」：事件处理器在**点击那一刻**才执行，那时节点上的
 * env 早被下一行覆盖了，写入会落到别人家的行上。
 */
interface Bindable {

    /** 用本行的现场造一份副本。默认实现（不实现本接口）就是「自己」。 */
    fun bind(ctx: EvalContext, sink: DiagnosticSink): Node
}

/**
 * 可变的诊断出口。
 *
 * 绑定的副本按 `(node, env)` 记忆，而 `diagnosticSink` 是宿主每次重组都可能换的 lambda。
 * 把 sink 放进 `remember` 的 key 会让每一帧都重建副本（非受控输入框的内容就丢了），
 * 所以这里传一个稳定的转发器，每帧只更新它的目标。
 */
internal class SinkRef : DiagnosticSink {

    var target: DiagnosticSink = DiagnosticSink.None

    override fun report(diagnostic: Diagnostic) {
        target.report(diagnostic)
    }
}

/**
 * 取本行绑定后的节点。不是 [Bindable] 就原样返回（不额外分配，也不订阅任何东西）。
 *
 * 两个 `remember` 的 key 只有 `(node, ctx)`：`ctx` 由 `rememberEvalContext` 按
 * `(env, methods)` 记忆，所以行内稳定、跨行不同 —— 正是要的。
 */
@Composable
internal fun rememberBound(node: Node, env: Env): Node {
    if (node !is Bindable) return node

    val ctx = rememberEvalContext(env)
    val ref = remember(node, ctx) { SinkRef() }
    ref.target = LocalDiagnostics.current
    return remember(node, ctx) { node.bind(ctx, ref) }
}
