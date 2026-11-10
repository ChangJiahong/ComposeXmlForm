package cn.changjiahong.composexmlform.engine.syntax

import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.diag.SyntaxError

/**
 * 词法模式。两者唯一的差别是**换行算不算语句分隔**（X-5）。
 *
 * 只有一份词法器，一个开关 —— 这样 XML 属性里的 `{}` 与脚本体内的字符串 / 注释规则**必然一致**（D-6）。
 * 各写两套是「属性切分和词法理解不一致」这类诡异错误的唯一来源。
 */
enum class LexMode {

    /** XML 属性值：换行按空白处理（属性值可以跨行书写）。不支持 `//` 注释。 */
    XmlAttribute,

    /** 脚本：换行即分隔，`;` 可省，支持 `//` 注释。 */
    Script,
}

/**
 * 词法器。输出的每个 [Token] 都带 `start` / `end` 偏移与行列 —— 属性值内的错误要定位到
 * 「第几列」而不是「哪个标签」（D-3）。
 */
class Lexer(
    private val source: String,
    private val mode: LexMode = LexMode.Script,
) {

    private var index = 0
    private var line = 1
    private var column = 1

    fun tokens(): List<Token> {
        val out = ArrayList<Token>(16)
        while (true) {
            val token = next()
            if (token.kind == TokenKind.Newline && out.lastOrNull()?.kind == TokenKind.Newline) continue
            out += token
            if (token.kind == TokenKind.Eof) return out
        }
    }

    private fun next(): Token {
        while (true) {
            skipBlanks()
            if (index >= source.length) return Token(TokenKind.Eof, "", here(0))
            if (peek() != '\n') break

            val from = index
            val atLine = line
            val atColumn = column
            advance()
            if (mode == LexMode.Script) {
                return Token(TokenKind.Newline, "\n", Span(from, index, atLine, atColumn))
            }
        }

        val start = here(0)
        val c = advance()

        return when {
            c == '"' || c == '\'' -> string(c, start)
            c.isDigit() -> number(c, start)
            c.isLetter() || c == '_' -> identifier(c, start)
            else -> punctuation(c, start)
        }
    }

    /** 跳过空白与注释。换行留给 [next] 处理（脚本模式下它是 token）。 */
    private fun skipBlanks() {
        while (index < source.length) {
            when {
                peek() == ' ' || peek() == '\t' || peek() == '\r' -> advance()
                peek() == '/' && peek(1) == '/' && mode == LexMode.Script -> {
                    while (index < source.length && peek() != '\n') advance()
                }

                else -> return
            }
        }
    }

    private fun string(quote: Char, start: Span): Token {
        val text = StringBuilder()
        while (true) {
            if (index >= source.length) {
                throw SyntaxError("字符串没有闭合（缺少 $quote）", start)
            }
            val c = advance()
            if (c == quote) break
            if (c == '\\' && index < source.length) {
                text.append(
                    when (val escaped = advance()) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> escaped
                    },
                )
            } else {
                text.append(c)
            }
        }
        return Token(TokenKind.String, text.toString(), start.spanTo(here(0)), text.toString())
    }

    private fun number(first: Char, start: Span): Token {
        val text = StringBuilder().append(first)
        var isFloat = false
        while (index < source.length) {
            val c = peek() ?: break
            when {
                c.isDigit() -> text.append(advance())
                c == '.' && !isFloat && peek(1)?.isDigit() == true -> {
                    isFloat = true
                    text.append(advance())
                }

                else -> break
            }
        }
        val raw = text.toString()
        val value: Any = if (isFloat) {
            raw.toDoubleOrNull() ?: throw SyntaxError("非法的数字『$raw』", start)
        } else {
            raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw SyntaxError("非法的数字『$raw』", start)
        }
        return Token(TokenKind.Number, raw, start.spanTo(here(0)), value)
    }

    private fun identifier(first: Char, start: Span): Token {
        val text = StringBuilder().append(first)
        while (index < source.length && (peek()?.isLetterOrDigit() == true || peek() == '_')) {
            text.append(advance())
        }
        val raw = text.toString()
        val kind = when (raw) {
            "true" -> TokenKind.True
            "false" -> TokenKind.False
            "null" -> TokenKind.Null
            "val" -> TokenKind.Val
            "var" -> TokenKind.Var
            "if" -> TokenKind.If
            "else" -> TokenKind.Else
            "return" -> TokenKind.Return
            "await" -> TokenKind.Await
            else -> TokenKind.Identifier
        }
        val value = when (kind) {
            TokenKind.True -> true
            TokenKind.False -> false
            else -> null
        }
        return Token(kind, raw, start.spanTo(here(0)), value)
    }

    private fun punctuation(c: Char, start: Span): Token {
        fun token(kind: TokenKind, text: String) = Token(kind, text, start.spanTo(here(0)))

        return when (c) {
            '.' -> token(TokenKind.Dot, ".")
            ',' -> token(TokenKind.Comma, ",")
            ':' -> token(TokenKind.Colon, ":")
            '(' -> token(TokenKind.LParen, "(")
            ')' -> token(TokenKind.RParen, ")")
            '[' -> token(TokenKind.LBracket, "[")
            ']' -> token(TokenKind.RBracket, "]")
            '{' -> token(TokenKind.LBrace, "{")
            '}' -> token(TokenKind.RBrace, "}")
            ';' -> token(TokenKind.Semicolon, ";")
            '+' -> token(TokenKind.Plus, "+")
            '-' -> if (peek() == '>') {
                advance()
                token(TokenKind.Arrow, "->")
            } else {
                token(TokenKind.Minus, "-")
            }

            '*' -> token(TokenKind.Star, "*")
            '/' -> token(TokenKind.Slash, "/")
            '%' -> token(TokenKind.Percent, "%")
            '!' -> when (peek()) {
                '=' -> {
                    advance()
                    if (peek() == '=') {
                        advance()
                        token(TokenKind.NotEqEq, "!==")
                    } else {
                        token(TokenKind.NotEq, "!=")
                    }
                }

                else -> token(TokenKind.Not, "!")
            }

            '=' -> when (peek()) {
                '=' -> {
                    advance()
                    if (peek() == '=') {
                        advance()
                        token(TokenKind.EqEqEq, "===")
                    } else {
                        token(TokenKind.EqEq, "==")
                    }
                }

                else -> token(TokenKind.Eq, "=")
            }

            '<' -> if (peek() == '=') {
                advance()
                token(TokenKind.LtEq, "<=")
            } else {
                token(TokenKind.Lt, "<")
            }

            '>' -> if (peek() == '=') {
                advance()
                token(TokenKind.GtEq, ">=")
            } else {
                token(TokenKind.Gt, ">")
            }

            '&' -> if (peek() == '&') {
                advance()
                token(TokenKind.AndAnd, "&&")
            } else {
                throw SyntaxError("单个 & 不合法，逻辑与请写 &&", start)
            }

            '|' -> if (peek() == '|') {
                advance()
                token(TokenKind.OrOr, "||")
            } else {
                throw SyntaxError("单个 | 不合法，逻辑或请写 ||", start)
            }

            '?' -> if (peek() == ':') {
                advance()
                token(TokenKind.Elvis, "?:")
            } else {
                throw SyntaxError("不支持 ?. 与单个 ?：路径访问本身是安全的，空合并请写 ?:", start)
            }

            else -> throw SyntaxError("无法识别的字符『$c』", start)
        }
    }

    private fun peek(offset: Int = 0): Char? = source.getOrNull(index + offset)

    private fun advance(): Char {
        val c = source[index]
        index++
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }

    private fun here(width: Int): Span = Span(index, index + width, line, column)
}

private fun Span.spanTo(end: Span): Span = Span(start, end.end, line, column)
