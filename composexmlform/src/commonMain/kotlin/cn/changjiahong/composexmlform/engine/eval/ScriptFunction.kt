package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.syntax.LambdaExpr
import cn.changjiahong.composexmlform.engine.syntax.Stmt
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * `@{ x -> … }` 的产物：把 AST 包成 [Function]。
 *
 * **不捕获定义处的局部变量**（S-1）。只握着 [env] 用来查全局对象，于是：
 *
 * ```
 * @{ x -> data.list.append(x) }     // 每次调用都读当时的 data
 * @{ x -> local }                   // local 是定义处的局部量 —— 读不到，解析成 data.local
 * ```
 *
 * 这是刻意的：`Foreach` 一份节点渲染 N 行，若闭包捕获行变量，第 3 行的事件处理器
 * 会带着第 1 行创建时的快照。视图层的行状态由 `Slot` 的响应式承担，不靠闭包。
 *
 * [hint] 跟着函数走：`onClick=@{ click() }` 里的 `click` 要兜底到 `action`（V-13），
 * 而这个兜底依据来自**声明这个槽位的属性类型**，只有创建时知道。
 */
internal class ScriptFunction(
    private val params: List<String>,
    private val body: List<Stmt>,
    private val env: Env,
    private val hint: SlotKind,
    private val methods: MethodExtensions,
    val span: Span? = null,
) : Function {

    override fun invoke(target: Value, args: List<Value>): Value {
        val frame = CallFrame()
        params.forEachIndexed { index, name ->
            // 参数按位置绑定（X-2）。少给的补 Absent、多给的忽略：
            // `onClick` 可能被 0 个或 1 个实参触发，严格校验反而会拒绝合法用法。
            frame.declare(name, args.getOrElse(index) { Absent }, mutable = false)
        }
        return Evaluator.runBody(body, EvalContext(env, frame, args, hint, methods))
    }

    override fun toString(): String =
        if (params.isEmpty()) "<函数>" else "<函数(${params.joinToString()})>"
}

/** 求值 `@{…}`：把 lambda 包成可以传参、可以存进 `data` 的一等值（F-5）。 */
internal fun lambdaFunction(lambda: LambdaExpr, ctx: EvalContext): ScriptFunction =
    ScriptFunction(
        params = lambda.params,
        body = lambda.body,
        env = ctx.env,
        hint = ctx.hint,
        methods = ctx.methods,
        span = lambda.span,
    )
