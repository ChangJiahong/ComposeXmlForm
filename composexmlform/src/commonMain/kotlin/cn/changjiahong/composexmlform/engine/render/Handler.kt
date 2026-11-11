package cn.changjiahong.composexmlform.engine.render

import cn.changjiahong.composexmlform.engine.eval.EvalContext
import cn.changjiahong.composexmlform.engine.eval.lambdaFunction
import cn.changjiahong.composexmlform.engine.node.AttrRef
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 事件回调：**捕获局部绑定的值快照 + 根 env 引用**（R-3）。
 *
 * 为什么不是普通闭包：`@{…}` 的语义是「在渲染这一行时的上下文里执行」。
 * 那个上下文由 [captured]（`item` / `index` 的当前值）与根 env（`data` / `config` /
 * `action` 的**执行时**状态）共同决定 —— 这个组合只有渲染层知道怎么取。
 *
 * 两种极端都不对：
 *
 * | 捕获方式 | 问题 |
 * | --- | --- |
 * | 直接持有整条 env | 行被移除后 env 树无法回收；且会读到「已经不属于任何行」的槽位 |
 * | 什么都不捕获（纯闭包） | `@{ x -> item.id }` 里的 `item` 只能落到 `data.item`，永远读不到 |
 *
 * 全局对象**不进快照**：`data.user.name` 必须是「点击那一刻」的值，不是渲染时的。
 */
class EventHandler internal constructor(
    private val function: Function,
    /** 创建时局部变量的值快照。内层遮蔽外层，与作用域链同一规则。 */
    val captured: Map<String, Value>,
) {

    /** 触发。没有参数的回调（`onClick`）就是 `invoke()`。 */
    operator fun invoke(args: List<Value> = emptyList()): Value = function.invoke(null, args)

    /** 给 Compose 的 `() -> Unit` 回调直接用。 */
    fun callback(): () -> Unit = { invoke() }

    override fun toString(): String =
        if (captured.isEmpty()) "EventHandler" else "EventHandler(${captured.keys.joinToString()})"
}

/**
 * 把一个函数槽位读成 [EventHandler]。**给「自己记忆化」的调用方用**：
 * 属性委托（`val onClick by props.action("onClick")`）不走 composition，没有 `remember`，
 * 快照比较与重建由委托自己负责。
 *
 * `captured` 是每帧现算的（[cn.changjiahong.composexmlform.engine.env.Env.captureLocals]），
 * 读它会让调用方订阅全部局部变量 —— 正是想要的：它们就是回调的输入。
 */
internal fun AttrSlot<Function>.buildHandler(
    ctx: EvalContext,
    captured: Map<String, Value>,
    sink: DiagnosticSink,
): EventHandler? {
    val ref = ref

    // `{{action.login}}`：绑定到宿主已有的函数，没有定义处上下文要捕获
    if (ref !is AttrRef.Lambda) {
        val fn = renderValue(ctx, sink) ?: return null
        return EventHandler(fn, captured)
    }

    // `@{…}`：用「根 env + 快照」重建一层现场。不捕获整条链 —— 链里有别人家的行变量
    val eventEnv = ctx.env.root().child()
    for ((name, value) in captured) eventEnv.define(name, value)

    val fn = lambdaFunction(
        lambda = ref.expr,
        ctx = EvalContext(eventEnv, methods = ctx.methods, hint = def.slotKind),
    )
    return EventHandler(fn, captured)
}
