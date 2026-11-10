package cn.changjiahong.composexmlform.engine.syntax

import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.diag.SyntaxError

/**
 * 递归下降 + 优先级爬升。
 *
 * 语法对齐 Kotlin（K-1～K-7）：`val` / `var`、`?:`、`==` 结构相等、`===` 引用相等、
 * `if` 是表达式、`;` 与换行都可作分隔。削掉的是 `when` / `for` / `class` / 类型标注 / 字符串模板。
 *
 * 只在**解析期**运行一次，结果（AST）被节点树持有，重组多少次都不重新解析。
 */
internal class Parser(private val tokens: List<Token>) {

    private var pos = 0

    // ── 入口 ──────────────────────────────────────────────────

    fun expressionOnly(): Expr {
        skipSeparators()
        val expr = expression()
        skipSeparators()
        expect(TokenKind.Eof, "表达式结尾")
        return expr
    }

    fun programOnly(): List<Stmt> {
        val out = statementsUntilEof()
        expect(TokenKind.Eof, "脚本结尾")
        return out
    }

    fun lambdaOnly(): LambdaExpr {
        val params = takeParams()
        val body = statementsUntilEof()
        expect(TokenKind.Eof, "函数体结尾")
        return LambdaExpr(params, body, spanOf(tokens.first().span, tokens.last().span))
    }

    // ── 语句 ──────────────────────────────────────────────────

    private fun statementsUntilEof(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (true) {
            skipSeparators()
            if (check(TokenKind.Eof) || check(TokenKind.RBrace)) return out
            out += statement()
        }
    }

    private fun statement(): Stmt {
        val token = peek()
        return when (token.kind) {
            TokenKind.Val, TokenKind.Var -> varDecl()
            TokenKind.Return -> returnStmt()
            TokenKind.Await -> {
                val expr = awaitOrExpression()
                Stmt.ExprStmt(expr, expr.span)
            }

            TokenKind.LBrace -> {
                val block = block()
                Stmt.ExprStmt(block, block.span)
            }

            else -> {
                val start = token.span
                val expr = expression()
                if (check(TokenKind.Eq)) {
                    advance()
                    skipSeparators()
                    val value = awaitOrExpression()
                    if (!expr.isAssignable()) {
                        throw SyntaxError("赋值左侧只能是变量或成员访问", expr.span)
                    }
                    Stmt.Assign(expr, value, spanOf(start, value.span))
                } else {
                    Stmt.ExprStmt(expr, expr.span)
                }
            }
        }
    }

    private fun varDecl(): Stmt {
        val keyword = advance()
        val mutable = keyword.kind == TokenKind.Var
        val name = expect(TokenKind.Identifier, "变量名")
        expect(TokenKind.Eq, "=")
        skipSeparators()
        val init = awaitOrExpression()
        return Stmt.VarDecl(name.text, mutable, init, spanOf(keyword.span, init.span))
    }

    private fun returnStmt(): Stmt {
        val keyword = advance()
        val next = peek()
        val value = if (next.kind.isSeparator() || next.kind == TokenKind.RBrace || next.kind == TokenKind.Eof) {
            null
        } else {
            awaitOrExpression()
        }
        return Stmt.Return(value, spanOf(keyword.span, (value?.span ?: keyword.span)))
    }

    /**
     * `await` 只能出现在**语句位置或赋值右侧**（F-4 / P-5）。
     *
     * 允许它出现在任意表达式位置会逼求值器在挂起上下文里做算术 —— 那就得写第二套 suspend 求值器，
     * 而「只有一份求值器」是这套设计的地基。这里的收口方式：解析 `await call(…)` 之后，
     * 紧跟的 token 必须是分隔符 / 收尾括号，否则当场报错。
     */
    private fun awaitOrExpression(): Expr {
        if (!check(TokenKind.Await)) return expression()

        val keyword = advance()
        skipSeparators()
        val operand = postfix()
        if (operand !is Expr.Call) {
            throw SyntaxError(
                "await 的操作数必须是函数调用，例如 await http.get(url)",
                operand.span,
            )
        }
        val following = peek()
        if (!following.kind.isSeparator() && following.kind != TokenKind.Eof &&
            following.kind != TokenKind.RBrace && following.kind != TokenKind.RParen &&
            following.kind != TokenKind.Comma
        ) {
            throw SyntaxError(
                "await 的结果不能直接参与运算，请先赋给变量：val r = await f(x)",
                following.span,
            )
        }
        return Expr.Await(operand, spanOf(keyword.span, operand.span))
    }

    private fun block(): Expr.Block {
        val open = expect(TokenKind.LBrace, "{")
        val body = ArrayList<Stmt>()
        while (true) {
            skipSeparators()
            if (check(TokenKind.RBrace)) break
            if (check(TokenKind.Eof)) throw SyntaxError("代码块没有闭合", open.span)
            body += statement()
        }
        val close = expect(TokenKind.RBrace, "}")
        return Expr.Block(body, spanOf(open.span, close.span))
    }

    // ── 表达式 ────────────────────────────────────────────────

    fun expression(): Expr = binary(1)

    private fun binary(minPrecedence: Int): Expr {
        var left = unary()
        while (true) {
            val op = peek()
            val current = precedence(op.kind)
            if (current == 0 || current < minPrecedence) return left

            advance()
            skipSeparators() // 允许 `a +\n b` 与 `a +\n b` 这类跨行续写
            val nextMin = if (op.kind == TokenKind.Elvis) current else current + 1
            val right = binary(nextMin)
            left = Expr.Binary(op.kind, left, right, spanOf(left.span, right.span))
        }
    }

    private fun unary(): Expr {
        val token = peek()
        if (token.kind == TokenKind.Not || token.kind == TokenKind.Minus) {
            advance()
            val operand = unary()
            return Expr.Unary(token.kind, operand, spanOf(token.span, operand.span))
        }
        return postfix()
    }

    private fun postfix(): Expr {
        var expr = primary()
        while (true) {
            when (peek().kind) {
                TokenKind.Dot -> {
                    advance()
                    val name = expect(TokenKind.Identifier, "属性名")
                    expr = Expr.Get(expr, name.text, spanOf(expr.span, name.span))
                }

                TokenKind.LBracket -> {
                    advance()
                    skipSeparators()
                    val index = expression()
                    skipSeparators()
                    val close = expect(TokenKind.RBracket, "]")
                    expr = Expr.Index(expr, index, spanOf(expr.span, close.span))
                }

                TokenKind.LParen -> {
                    advance()
                    val args = arguments()
                    expr = Expr.Call(expr, args, spanOf(expr.span, previous().span))
                }

                else -> return expr
            }
        }
    }

    private fun arguments(): List<Expr> {
        val out = ArrayList<Expr>(2)
        skipSeparators()
        if (check(TokenKind.RParen)) {
            advance()
            return out
        }
        while (true) {
            skipSeparators()
            out += expression()
            skipSeparators()
            when {
                check(TokenKind.Comma) -> advance()
                check(TokenKind.RParen) -> {
                    advance()
                    return out
                }

                else -> throw SyntaxError("参数之间缺少 , 或 )", peek().span)
            }
        }
    }

    private fun primary(): Expr {
        val token = peek()
        return when (token.kind) {
            TokenKind.Number, TokenKind.String, TokenKind.True, TokenKind.False, TokenKind.Null -> {
                advance()
                Expr.Lit(literalValue(token), token.span)
            }

            TokenKind.Identifier -> {
                advance()
                Expr.Name(token.text, token.span)
            }

            TokenKind.LParen -> {
                val open = advance()
                skipSeparators()
                val inner = expression()
                skipSeparators()
                val close = expect(TokenKind.RParen, ")")
                // 括号只影响结合性，不产生节点；span 扩到括号，错误定位更直观
                withSpan(inner, spanOf(open.span, close.span))
            }

            TokenKind.LBracket -> arrayLiteral()
            TokenKind.LBrace -> objectLiteral()
            TokenKind.If -> ifExpression()
            TokenKind.At -> lambdaLiteral()
            else -> throw SyntaxError("期望表达式，遇到『${token}』", token.span)
        }
    }

    /**
     * `@{x -> …}` 作为表达式（F-5）。
     *
     * 属性里的 `@{}` 由 `AttrScanner` 整段切出来再调 [parseLambda]，所以这条路径只在
     * **脚本里**（`data.user.print = @{ … }`、`f(@{ … })`）用到。
     */
    private fun lambdaLiteral(): Expr {
        val at = advance()
        expect(TokenKind.LBrace, "{")
        val params = takeParams()
        val body = ArrayList<Stmt>()
        while (true) {
            skipSeparators()
            if (check(TokenKind.RBrace)) break
            if (check(TokenKind.Eof)) throw SyntaxError("@{ … } 没有闭合", at.span)
            body += statement()
        }
        val close = expect(TokenKind.RBrace, "}")
        return Expr.Lambda(LambdaExpr(params, body, spanOf(at.span, close.span)), spanOf(at.span, close.span))
    }

    private fun arrayLiteral(): Expr {
        val open = advance()
        val items = ArrayList<Expr>()
        skipSeparators()
        if (check(TokenKind.RBracket)) {
            val close = advance()
            return Expr.Arr(items, spanOf(open.span, close.span))
        }
        while (true) {
            skipSeparators()
            items += expression()
            skipSeparators()
            when {
                check(TokenKind.Comma) -> advance()
                check(TokenKind.RBracket) -> {
                    val close = advance()
                    return Expr.Arr(items, spanOf(open.span, close.span))
                }

                else -> throw SyntaxError("数组元素之间缺少 , 或 ]", peek().span)
            }
        }
    }

    private fun objectLiteral(): Expr {
        val open = advance()
        val entries = ArrayList<Pair<String, Expr>>()
        skipSeparators()
        if (check(TokenKind.RBrace)) {
            val close = advance()
            return Expr.Obj(entries, spanOf(open.span, close.span))
        }
        while (true) {
            skipSeparators()
            val key = when (peek().kind) {
                TokenKind.Identifier, TokenKind.String -> advance().text
                else -> throw SyntaxError("对象键必须是标识符或字符串", peek().span)
            }
            expect(TokenKind.Colon, ":")
            skipSeparators()
            entries += key to expression()
            skipSeparators()
            when {
                check(TokenKind.Comma) -> advance()
                check(TokenKind.RBrace) -> {
                    val close = advance()
                    return Expr.Obj(entries, spanOf(open.span, close.span))
                }

                else -> throw SyntaxError("对象成员之间缺少 , 或 }", peek().span)
            }
        }
    }

    /** `if (c) a else b` —— 表达式（P-2）。分支可以是块，`else if` 直接嵌套。 */
    private fun ifExpression(): Expr {
        val keyword = advance()
        expect(TokenKind.LParen, "(")
        skipSeparators()
        val cond = expression()
        skipSeparators()
        expect(TokenKind.RParen, ")")
        skipSeparators()

        val thenExpr = branch()
        skipSeparators()
        val elseExpr = if (check(TokenKind.Else)) {
            advance()
            skipSeparators()
            branch()
        } else {
            null
        }
        return Expr.If(cond, thenExpr, elseExpr, spanOf(keyword.span, (elseExpr ?: thenExpr).span))
    }

    /**
     * `if` 的分支。`{ … }` 是块，其余是表达式。
     *
     * `return` / `await` 开头时包成单语句块：Kotlin 里它们本身就是表达式（`Nothing` 与挂起），
     * 我们不为它引入 AST 节点 —— 包成块之后求值路径与手写 `{ return 1 }` 完全一致。
     */
    private fun branch(): Expr = when {
        check(TokenKind.LBrace) -> block()
        check(TokenKind.Return) || check(TokenKind.Await) -> {
            val stmt = statement()
            Expr.Block(listOf(stmt), stmt.span)
        }

        else -> expression()
    }

    // ── 工具 ──────────────────────────────────────────────────

    /**
     * `x, y -> …` 的参数表。没有 `->` 就是无参（`@{click()}`）。
     *
     * 逐 token 看而不是 `indexOfFirst { Arrow }`：后者在嵌套 lambda（`@{x -> f(@{y -> y})}`）
     * 里会找到外层箭头，把游标推错位置。
     */
    private fun takeParams(): List<String> {
        val names = ArrayList<String>(2)
        var index = pos
        var expectName = true
        while (index < tokens.size) {
            when (tokens[index].kind) {
                TokenKind.Arrow -> {
                    if (expectName && names.isNotEmpty()) return emptyList() // 逗号结尾，非法
                    pos = index + 1
                    return names
                }

                TokenKind.Identifier -> {
                    if (!expectName) return emptyList() // 形如 `x y`，非法
                    names += tokens[index].text
                    expectName = false
                    index++
                }

                TokenKind.Comma -> {
                    if (expectName) return emptyList() // 连续逗号或以逗号开头
                    expectName = true
                    index++
                }

                else -> return emptyList() // 第一条语句不是参数表
            }
        }
        return emptyList()
    }

    private fun literalValue(token: Token): Any? = when (token.kind) {
        TokenKind.Number -> token.value
        TokenKind.String -> token.value
        TokenKind.True -> true
        TokenKind.False -> false
        else -> null
    }

    private fun Expr.isAssignable(): Boolean =
        this is Expr.Name || this is Expr.Get || this is Expr.Index

    private fun withSpan(expr: Expr, span: Span): Expr = when (expr) {
        is Expr.Lit -> expr.copy(span = span)
        is Expr.Name -> expr.copy(span = span)
        is Expr.Get -> expr.copy(span = span)
        is Expr.Index -> expr.copy(span = span)
        is Expr.Call -> expr.copy(span = span)
        is Expr.Unary -> expr.copy(span = span)
        is Expr.Binary -> expr.copy(span = span)
        is Expr.Obj -> expr.copy(span = span)
        is Expr.Arr -> expr.copy(span = span)
        is Expr.If -> expr.copy(span = span)
        is Expr.Block -> expr.copy(span = span)
        is Expr.Await -> expr.copy(span = span)
        is Expr.Lambda -> expr.copy(span = span)
    }

    private fun precedence(kind: TokenKind): Int = when (kind) {
        TokenKind.Elvis -> 1
        TokenKind.OrOr -> 2
        TokenKind.AndAnd -> 3
        TokenKind.EqEq, TokenKind.NotEq, TokenKind.EqEqEq, TokenKind.NotEqEq -> 4
        TokenKind.Lt, TokenKind.LtEq, TokenKind.Gt, TokenKind.GtEq -> 5
        TokenKind.Plus, TokenKind.Minus -> 6
        TokenKind.Star, TokenKind.Slash, TokenKind.Percent -> 7
        else -> 0
    }

    private fun skipSeparators() {
        while (peek().kind.isSeparator()) advance()
    }

    private fun TokenKind.isSeparator(): Boolean =
        this == TokenKind.Newline || this == TokenKind.Semicolon

    private fun peek(offset: Int = 0): Token = tokens[(pos + offset).coerceAtMost(tokens.lastIndex)]

    private fun previous(): Token = tokens[(pos - 1).coerceAtLeast(0)]

    private fun check(kind: TokenKind): Boolean = peek().kind == kind

    private fun advance(): Token {
        val token = peek()
        if (pos < tokens.lastIndex) pos++
        return token
    }

    private fun expect(kind: TokenKind, what: String): Token {
        val token = peek()
        if (token.kind != kind) {
            throw SyntaxError("期望 $what，遇到『$token』", token.span)
        }
        return advance()
    }
}

// ── 便捷入口 ──────────────────────────────────────────────────

/** 解析单个表达式。XML 属性里的 `{…}` 用 [LexMode.XmlAttribute]（换行按空白）。 */
internal fun parseExpression(source: String, mode: LexMode = LexMode.XmlAttribute): Expr =
    Parser(Lexer(source, mode).tokens()).expressionOnly()

/** 解析脚本体（多条语句，换行即分隔）。 */
internal fun parseStatements(source: String, mode: LexMode = LexMode.Script): List<Stmt> =
    Parser(Lexer(source, mode).tokens()).programOnly()

/** 解析 `@{…}` 的内部内容。 */
internal fun parseLambda(source: String, mode: LexMode = LexMode.XmlAttribute): LambdaExpr =
    Parser(Lexer(source, mode).tokens()).lambdaOnly()
