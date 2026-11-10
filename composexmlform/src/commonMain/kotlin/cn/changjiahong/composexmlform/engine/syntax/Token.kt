package cn.changjiahong.composexmlform.engine.syntax

import cn.changjiahong.composexmlform.engine.diag.Span

/**
 * 词法单元种类。
 *
 * 关键字是**小写固定词**（`val` / `var` / `if` / `else` / `return` / `await` / `true` / `false` / `null`），
 * 与 Kotlin 一致（K-1）。`when` / `for` / `while` 故意不存在 —— 它们在 XML 引擎里没有对位的语义
 * （多分支用 `if` 链，迭代用 `Foreach`）。
 */
enum class TokenKind {

    // 字面量
    Number,
    String,
    Identifier,

    // 关键字
    True,
    False,
    Null,
    Val,
    Var,
    If,
    Else,
    Return,
    Await,

    // 标点
    Dot,
    Comma,
    Colon,
    LParen,
    RParen,
    LBracket,
    RBracket,
    LBrace,
    RBrace,
    Arrow,

    /** `@`：函数字面量的前缀（`@{x -> …}`）。属性里的 `@{}` 由 AttrScanner 剥离，脚本里由解析器处理。 */
    At,

    /** 语句分隔符。XML 属性里换行按空白处理，多条语句只能靠它分隔。 */
    Semicolon,

    // 运算符
    Plus,
    Minus,
    Star,
    Slash,
    Percent,
    Eq,
    EqEq,
    EqEqEq,
    NotEq,
    NotEqEq,
    Not,
    Lt,
    LtEq,
    Gt,
    GtEq,
    AndAnd,
    OrOr,
    Elvis,

    /** 换行。只在脚本模式产生；XML 属性模式里换行按空白处理（X-5）。 */
    Newline,

    Eof,
}

/** 一个词法单元。[value] 只对字面量有意义（数字已解析成 `Long` / `Double`）。 */
class Token(
    val kind: TokenKind,
    val text: String,
    val span: Span,
    val value: Any? = null,
) {

    override fun toString(): String = when (kind) {
        TokenKind.String -> "\"$text\""
        TokenKind.Eof -> "结束"
        else -> text
    }
}
