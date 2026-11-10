package cn.changjiahong.composexmlform.engine.parse

import cn.changjiahong.composexmlform.engine.syntax.Expr
import cn.changjiahong.composexmlform.engine.syntax.Stmt

/**
 * 脚本能力档位（P-7）。
 *
 * | 档位 | `{{}}` | `{}` | `@{}` | 赋值 | `await` |
 * | --- | --- | --- | --- | --- | --- |
 * | [NoScript] | ✅ | 报错 | 报错 | — | — |
 * | [ExpressionsOnly] | ✅ | ✅ 只读 | ✅ 只读 | 报错 | 报错 |
 * | [Full] | ✅ | ✅ | ✅ | ✅ | ✅ |
 *
 * 默认是 [ExpressionsOnly]：**表单的绝大多数场景只是算个显示值**，
 * 而「模板里能不能改数据」是一个产品决策，不该由引擎默认替使用者做。
 * 需要写的时候由宿主显式升档 —— 升档是一次可见的决定，不是一次疏忽。
 */
enum class ScriptMode {
    NoScript,
    ExpressionsOnly,
    Full,
}

/**
 * AST 副作用审计：回答「这段脚本会不会改动状态」。
 *
 * 静态判定而不是运行期拦截，是为了**在解析期就报错**（D-4 收集诊断）：
 * 运行期才发现「模板在改数据」意味着页面已经跑起来一半了。
 *
 * 注意它只看**语法**：`f()` 这种调用不改状态也判为「无副作用」——
 * 我们无法静态知道被调函数做什么，那是 M-3 明确不校验的部分。
 */
internal object ScriptAudit {

    /** `{}` 里的表达式有没有写操作。 */
    fun exprHasSideEffect(expr: Expr): Boolean = when (expr) {
        is Expr.Await -> true
        is Expr.Block -> statementsHaveSideEffect(expr.statements)
        is Expr.If -> exprHasSideEffect(expr.cond) ||
            exprHasSideEffect(expr.thenExpr) ||
            (expr.elseExpr?.let { exprHasSideEffect(it) } ?: false)

        is Expr.Binary -> exprHasSideEffect(expr.left) || exprHasSideEffect(expr.right)
        is Expr.Unary -> exprHasSideEffect(expr.operand)
        is Expr.Get -> exprHasSideEffect(expr.target)
        is Expr.Index -> exprHasSideEffect(expr.target) || exprHasSideEffect(expr.index)
        is Expr.Call -> exprHasSideEffect(expr.callee) || expr.args.any { exprHasSideEffect(it) }
        is Expr.Obj -> expr.entries.any { exprHasSideEffect(it.second) }
        is Expr.Arr -> expr.items.any { exprHasSideEffect(it) }
        // 嵌套 lambda 只在**被调用**时才执行，定义它本身没有副作用
        is Expr.Lambda -> false
        is Expr.Lit, is Expr.Name -> false
    }

    /** `@{}` 的函数体有没有写操作。 */
    fun statementsHaveSideEffect(statements: List<Stmt>): Boolean = statements.any { statement ->
        when (statement) {
            is Stmt.VarDecl -> true
            is Stmt.Assign -> true
            is Stmt.Return -> statement.value?.let { exprHasSideEffect(it) } ?: false
            is Stmt.ExprStmt -> exprHasSideEffect(statement.expr)
        }
    }

    /** 有没有 `await` —— 它比一般副作用更严格，[ScriptMode.Full] 之外一律拒绝。 */
    fun containsAwait(statements: List<Stmt>): Boolean = statements.any { statement ->
        when (statement) {
            is Stmt.VarDecl -> exprHasAwait(statement.init)
            is Stmt.Assign -> exprHasAwait(statement.value)
            is Stmt.Return -> statement.value?.let { exprHasAwait(it) } ?: false
            is Stmt.ExprStmt -> exprHasAwait(statement.expr)
        }
    }

    fun exprHasAwait(expr: Expr): Boolean = when (expr) {
        is Expr.Await -> true
        is Expr.Block -> containsAwait(expr.statements)
        is Expr.If -> exprHasAwait(expr.cond) ||
            exprHasAwait(expr.thenExpr) ||
            (expr.elseExpr?.let { exprHasAwait(it) } ?: false)

        is Expr.Binary -> exprHasAwait(expr.left) || exprHasAwait(expr.right)
        is Expr.Unary -> exprHasAwait(expr.operand)
        is Expr.Get -> exprHasAwait(expr.target)
        is Expr.Index -> exprHasAwait(expr.target) || exprHasAwait(expr.index)
        is Expr.Call -> exprHasAwait(expr.callee) || expr.args.any { exprHasAwait(it) }
        is Expr.Obj -> expr.entries.any { exprHasAwait(it.second) }
        is Expr.Arr -> expr.items.any { exprHasAwait(it) }
        is Expr.Lambda -> containsAwait(expr.lambda.body)
        is Expr.Lit, is Expr.Name -> false
    }
}
