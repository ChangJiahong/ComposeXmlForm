package cn.changjiahong.composexmlform.engine.node

import cn.changjiahong.composexmlform.engine.syntax.Expr
import cn.changjiahong.composexmlform.engine.syntax.LambdaExpr
import cn.changjiahong.composexmlform.engine.value.Path

/**
 * 属性值的**引擎内部表示**：四种括号的定稿形态（第 11 轮）+ 「没写」。
 *
 * 整型 `internal` 而不是公开：它挂着 `Expr` / `LambdaExpr`，而这两个是引擎的私有资产（M-5）。
 * 宿主写的是源码字符串，读属性走 `AttrSlot.read`，永远不需要看到这一层 —— 一旦公开，
 * 等于承诺 AST 形状永不变。
 *
 * 第五个分支 [Absent] 不在四种括号里：它表示「这个属性根本没写」。有它 `AttrSlot` 就不需要
 * 额外的 `present` 布尔，且 `read` 的默认值分支与「写了 `null`」不会被混为一谈。
 */
internal sealed interface AttrRef {

    /** 属性没写。读取时走 `AttrDef.default`。 */
    object Absent : AttrRef

    /** `"raw"` —— 字面量，按槽位适配器解释（`"3"` 对数字槽位是 `3L`）。 */
    data class Literal(val raw: String) : AttrRef

    /** `{{path}}` —— 数据绑定，**唯一可写回**的形态（X-1）。 */
    data class Bind(val path: Path) : AttrRef

    /** `{expr}` —— 闭包，求值时执行并取返回值。 */
    data class Code(val expr: Expr) : AttrRef

    /** `@{x -> …}` —— 函数引用，渲染时才包成回调（N-3）。 */
    data class Lambda(val expr: LambdaExpr) : AttrRef
}

/** 错误消息里说「你写的是哪种形态」。 */
internal fun AttrRef.describe(): String = when (this) {
    AttrRef.Absent -> "空"
    is AttrRef.Literal -> "字面量『$raw』"
    is AttrRef.Bind -> "{{${path.display()}}}"
    is AttrRef.Code -> "{…}"
    is AttrRef.Lambda -> "@{…}"
}
