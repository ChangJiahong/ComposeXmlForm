package cn.changjiahong.composexmlform.engine.syntax

import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 表达式 AST。**全部 `internal` 可见**（M-5）：宿主写的是源码字符串，编译在解析期完成，
 * 不需要看到树的形状 —— 一旦公开，等于承诺这个结构永不变。
 *
 * 每个节点都带 [span]。不是为了好看：`Absent + 1` 这类运行期错误必须报到具体列上（P-4）。
 */
internal sealed interface Expr {

    val span: Span

    /** 字面量：数字 / 字符串 / 布尔 / null。 */
    data class Lit(val value: Value, override val span: Span) : Expr

    /** 裸标识符。解析成什么交给求值器（局部 → 链 → 全局 → 兜底）。 */
    data class Name(val id: String, override val span: Span) : Expr

    /** `a.b`。名字是静态的，不需要求值。 */
    data class Get(val target: Expr, val name: String, override val span: Span) : Expr

    /** `a[i]`。下标是表达式，与 [Get] 分开。 */
    data class Index(val target: Expr, val index: Expr, override val span: Span) : Expr

    /** `f(a, b)` / `a.f(x)`。`a.f(x)` 的 `target` 由求值器从 [callee] 的接收者取出（第 5 轮）。 */
    data class Call(val callee: Expr, val args: List<Expr>, override val span: Span) : Expr

    data class Unary(val op: TokenKind, val operand: Expr, override val span: Span) : Expr

    data class Binary(val op: TokenKind, val left: Expr, val right: Expr, override val span: Span) : Expr

    /** `{name: expr}`。键是静态字符串。 */
    data class Obj(val entries: List<Pair<String, Expr>>, override val span: Span) : Expr

    /** `[a, b]`。 */
    data class Arr(val items: List<Expr>, override val span: Span) : Expr

    /** `if (c) a else b` —— 是**表达式**（P-2），两个分支都可以是 [Block]。 */
    data class If(
        val cond: Expr,
        val thenExpr: Expr,
        val elseExpr: Expr?,
        override val span: Span,
    ) : Expr

    /** `{ stmt; stmt }`。作为表达式时求值为最后一个语句的表达式值。 */
    data class Block(val statements: List<Stmt>, override val span: Span) : Expr

    /**
     * `await f(x)`。操作数**必须是函数调用**（F-4）。
     *
     * 做成表达式而不是语句（PLAN I-9 的偏差），是因为 `val r = await f(x)` 是唯一自然的写法 ——
     * 语句节点装不进 `VarDecl.init`。它仍然只能出现在**语句位置或赋值右侧**：
     * 解引号 `await f(x) + 1` 在解析期就被拒（求值器没有挂起版本的算术）。
     */
    data class Await(val call: Call, override val span: Span) : Expr

    /**
     * `@{x -> …}` 作为**表达式**（F-5）：可以赋给 `data` 的成员、当作实参、放进字面量。
     *
     * 与属性里的 `@{}` 走同一套 [LambdaExpr]，区别只在谁把它包成值：
     * 属性由渲染层包（因为要按槽位类型定兜底），这里由求值器包。
     */
    data class Lambda(val lambda: LambdaExpr, override val span: Span) : Expr
}

/** 语句。表达式全集 + 赋值、声明、返回。 */
internal sealed interface Stmt {

    val span: Span

    /** `val x = 1` / `var x = 1`。落在 `CallFrame`，**不进 env 链**（P-1）。 */
    data class VarDecl(
        val name: String,
        val mutable: Boolean,
        val init: Expr,
        override val span: Span,
    ) : Stmt

    /** `target = value`。target 只能是 [Expr.Name] 或 [Expr.Get] / [Expr.Index]。 */
    data class Assign(val target: Expr, val value: Expr, override val span: Span) : Stmt

    data class Return(val value: Expr?, override val span: Span) : Stmt

    data class ExprStmt(val expr: Expr, override val span: Span) : Stmt
}

/**
 * `@{x -> …}` 的产物。
 *
 * 参数**不带类型**，按位置绑定（X-2）。实参由调用方给：`onClick` 无参、列表项传入行号。
 */
internal data class LambdaExpr(
    val params: List<String>,
    val body: List<Stmt>,
    val span: Span,
)

/** 两段合并成一个跨度，用于「从 A 到 B」的节点（Call、Binary、If）。 */
internal fun spanOf(first: Span, last: Span): Span = first.merge(last)
