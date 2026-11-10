package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.EvalError
import cn.changjiahong.composexmlform.engine.syntax.Expr
import cn.changjiahong.composexmlform.engine.syntax.Stmt
import cn.changjiahong.composexmlform.engine.syntax.parseStatements
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.SuspendFunction
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 脚本执行的挂起外壳。**不是第二个求值器**（第 14 轮的红线 5）。
 *
 * 它只做三件事，其余全部转交 [Evaluator]：
 * 1. `await f(x)`：拿到 `PreparedCall` 后走 `invokeSuspend`；
 * 2. `if` 分支与块：因为里面可能藏 `await`，必须在这一层递归下去；
 * 3. 其余语句 / 表达式：原样调用同步求值器。
 *
 * 为什么可以这样切：`await` 的语法收口在「语句位置或赋值右侧，且操作数必须是函数调用」（F-4），
 * 所以**算术里永远不会有 await** —— 同步求值器不需要挂起版本。
 *
 * 一个已知边界：`@{}` 函数体不支持 `await`（[ScriptFunction] 实现的是同步 [Function]）。
 * 属性里的回调由 Compose 同步触发，本来也无法挂起；需要挂起的能力应当由宿主注册
 * [SuspendFunction]，在脚本语句里 `await` 它。
 */
internal suspend fun execute(block: Expr.Block, ctx: EvalContext): Value =
    execute(block.statements, ctx)

internal suspend fun execute(statements: List<Stmt>, ctx: EvalContext): Value {
    var last: Value = null
    for (stmt in statements) {
        if (ctx.frame?.returned == true) break
        last = execStatement(stmt, ctx)
    }
    return last
}

/** 执行一个脚本函数体并取出 `return` 的值。 */
internal suspend fun runBodySuspending(statements: List<Stmt>, ctx: EvalContext): Value {
    val last = execute(statements, ctx)
    val frame = ctx.frame
    return if (frame?.returned == true) frame.returnValue else last
}

/** 从源码执行一段脚本。宿主的动作脚本入口（S8 会包成 `ScriptRuntime.execute`）。 */
internal suspend fun executeScript(source: String, ctx: EvalContext): Value {
    val frame = ctx.frame ?: CallFrame()
    return runBodySuspending(parseStatements(source), ctx.withFrame(frame))
}

/**
 * 语句分发。`VarDecl` / `Assign` / `Return` 的**右侧**可能藏 `await`（`val r = await f(x)`），
 * 所以它们的值也必须在这个外壳里求；求出值之后，落位逻辑仍然复用同步求值器的那一份。
 */
private suspend fun execStatement(stmt: Stmt, ctx: EvalContext): Value = when (stmt) {
    is Stmt.VarDecl -> {
        val value = evalSuspending(stmt.init, ctx)
        Evaluator.declare(stmt, value, ctx)
        value
    }

    is Stmt.Assign -> {
        val value = evalSuspending(stmt.value, ctx)
        Evaluator.assignValue(stmt, value, ctx)
        value
    }

    is Stmt.Return -> {
        val value = stmt.value?.let { evalSuspending(it, ctx) }
        val frame = ctx.frame ?: throw EvalError("return 只能出现在脚本里", stmt.span)
        frame.signalReturn(value)
        value
    }

    is Stmt.ExprStmt -> evalSuspending(stmt.expr, ctx)
}

private suspend fun evalSuspending(expr: Expr, ctx: EvalContext): Value = when (expr) {
    is Expr.Await -> await(expr, ctx)

    is Expr.Block -> execute(expr.statements, ctx)

    is Expr.If -> {
        // 条件里不可能有 await（`{` 在表达式位置是对象字面量，块只出现在语句位置），
        // 所以条件照常用同步求值
        if (Evaluator.requireCondition(expr.cond, ctx)) {
            evalSuspending(expr.thenExpr, ctx)
        } else {
            expr.elseExpr?.let { evalSuspending(it, ctx) }
        }
    }

    else -> Evaluator.evaluate(expr, ctx)
}

private suspend fun await(expr: Expr.Await, ctx: EvalContext): Value {
    val prepared = Evaluator.prepareCall(expr.call, ctx)
    val fn = prepared.fn as? Function ?: throw Evaluator.callError(prepared)
    return if (fn is SuspendFunction) {
        fn.invokeSuspend(prepared.target, prepared.args)
    } else {
        // 同步函数也允许 await —— 等于不加，省掉用户判断「这个函数要不要挂起」
        fn.invoke(prepared.target, prepared.args)
    }
}
