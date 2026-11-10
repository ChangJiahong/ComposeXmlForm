package cn.changjiahong.composexmlform.engine.syntax

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.SyntaxError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 词法 / 语法 / 括号切分。
 *
 * 断言全部落在**结构**上（AST 形状、token 种类、错误码），不比具体偏移 ——
 * 偏移随实现微调，结构才是契约。
 */
class SyntaxTest {

    // ── 括号切分（第 11 轮定稿）──────────────────────────────

    @Test
    fun scansFourAttributeForms() {
        assertIs<AttrForm.Literal>(AttrScanner.scan("hello").formOrNull())
        assertIs<AttrForm.Binding>(AttrScanner.scan("{{user.name}}").formOrNull())
        assertIs<AttrForm.Code>(AttrScanner.scan("{a + b}").formOrNull())
        assertIs<AttrForm.Lambda>(AttrScanner.scan("@{x -> x}").formOrNull())
    }

    @Test
    fun bindingBodyIsTrimmed() {
        val form = assertIs<AttrForm.Binding>(AttrScanner.scan("{{  user.name  }}").formOrNull())
        assertEquals("user.name", form.body)
    }

    @Test
    fun escapedBracesBecomeLiteral() {
        val form = assertIs<AttrForm.Literal>(AttrScanner.scan("\\{abc\\}").formOrNull())
        assertEquals("{abc}", form.text)
    }

    @Test
    fun nestedBracesInCode() {
        val form = assertIs<AttrForm.Code>(AttrScanner.scan("{ if (a) { 1 } else { 2 } }").formOrNull())
        assertEquals(" if (a) { 1 } else { 2 } ", form.body)
    }

    @Test
    fun braceInsideStringDoesNotCloseBlock() {
        val form = assertIs<AttrForm.Code>(AttrScanner.scan("{ \"}\" + x }").formOrNull())
        assertTrue(form.body.contains("\"}\""))
    }

    @Test
    fun unclosedBlockReportsMalformedBraces() {
        val error = assertIs<ScanResult.Err>(AttrScanner.scan("{ a + b"))
        assertEquals(Code.MalformedBraces, error.code)
    }

    @Test
    fun emptyBindingIsItsOwnError() {
        val error = assertIs<ScanResult.Err>(AttrScanner.scan("{{  }}"))
        assertEquals(Code.EmptyBinding, error.code)
    }

    @Test
    fun unclosedBindingReportsMalformedBraces() {
        val error = assertIs<ScanResult.Err>(AttrScanner.scan("{{user.name"))
        assertEquals(Code.MalformedBraces, error.code)
    }

    @Test
    fun lambdaWithArrowIsNotMistakenForCode() {
        val form = assertIs<AttrForm.Lambda>(AttrScanner.scan("@{i -> action.pick(i)}").formOrNull())
        assertTrue(form.body.contains("->"))
    }

    // ── 词法 ─────────────────────────────────────────────────

    @Test
    fun numbersKeepIntegerAndFloatApart() {
        val tokens = Lexer("1 2.5").tokens()
        assertEquals(1L, tokens[0].value)
        assertEquals(2.5, tokens[1].value)
    }

    @Test
    fun stringsSupportBothQuotesAndEscapes() {
        val tokens = Lexer("""'a' "b\n" """.trim()).tokens()
        assertEquals("a", tokens[0].value)
        assertEquals("b\n", tokens[1].value)
    }

    @Test
    fun keywordsAreRecognised() {
        val kinds = Lexer("val var if else return await true false null").tokens().map { it.kind }
        assertEquals(
            listOf(
                TokenKind.Val, TokenKind.Var, TokenKind.If, TokenKind.Else,
                TokenKind.Return, TokenKind.Await, TokenKind.True, TokenKind.False,
                TokenKind.Null, TokenKind.Eof,
            ),
            kinds,
        )
    }

    @Test
    fun newlineIsTokenOnlyInScriptMode() {
        assertTrue(Lexer("a\nb", LexMode.Script).tokens().any { it.kind == TokenKind.Newline })
        assertTrue(Lexer("a\nb", LexMode.XmlAttribute).tokens().none { it.kind == TokenKind.Newline })
    }

    @Test
    fun commentSkippedInScriptModeOnly() {
        val script = Lexer("a // b\nc", LexMode.Script).tokens().map { it.kind }
        assertTrue(TokenKind.Identifier in script)
        assertEquals(2, script.count { it == TokenKind.Identifier })

        val attribute = Lexer("a // b", LexMode.XmlAttribute).tokens().map { it.kind }
        assertEquals(2, attribute.count { it == TokenKind.Slash }, "XML 属性模式下 // 是两次除号，不是注释")
    }

    @Test
    fun longestMatchForOperators() {
        val kinds = Lexer("=== !== == != ?: ->").tokens().map { it.kind }
        assertEquals(
            listOf(
                TokenKind.EqEqEq, TokenKind.NotEqEq, TokenKind.EqEq, TokenKind.NotEq,
                TokenKind.Elvis, TokenKind.Arrow, TokenKind.Eof,
            ),
            kinds,
        )
    }

    @Test
    fun safeCallOperatorIsRejected() {
        assertFailsWith<SyntaxError> { Lexer("a?.b").tokens() }
    }

    @Test
    fun tokensCarryLineAndColumn() {
        val tokens = Lexer("a\n  b", LexMode.Script)
            .tokens()
            .filter { it.kind == TokenKind.Identifier }
        assertEquals(1, tokens[0].span.line)
        assertEquals(2, tokens[1].span.line)
        assertEquals(3, tokens[1].span.column)
    }

    // ── 语法：优先级与结合性 ─────────────────────────────────

    @Test
    fun multiplicationBindsTighterThanAddition() {
        val expr = parseExpression("1 + 2 * 3")
        val add = assertIs<Expr.Binary>(expr)
        assertEquals(TokenKind.Plus, add.op)
        val mul = assertIs<Expr.Binary>(add.right)
        assertEquals(TokenKind.Star, mul.op)
    }

    @Test
    fun elvisIsRightAssociative() {
        val expr = assertIs<Expr.Binary>(parseExpression("a ?: b ?: c"))
        assertEquals(TokenKind.Elvis, expr.op)
        assertIs<Expr.Name>(expr.left)
        assertIs<Expr.Binary>(expr.right, "右结合：a ?: (b ?: c)")
    }

    @Test
    fun equalityAndReferenceEqualityAreDifferentTokens() {
        assertEquals(TokenKind.EqEq, assertIs<Expr.Binary>(parseExpression("a == b")).op)
        assertEquals(TokenKind.EqEqEq, assertIs<Expr.Binary>(parseExpression("a === b")).op)
    }

    @Test
    fun unaryBindsTighterThanBinary() {
        val expr = assertIs<Expr.Binary>(parseExpression("!a && b"))
        assertIs<Expr.Unary>(expr.left)
    }

    @Test
    fun comparisonChainsLeftToRight() {
        val expr = assertIs<Expr.Binary>(parseExpression("a < b == c"))
        assertEquals(TokenKind.EqEq, expr.op, "比较优先于相等")
    }

    // ── 语法：访问与调用 ─────────────────────────────────────

    @Test
    fun memberChainAndCallAndIndex() {
        val call = assertIs<Expr.Call>(parseExpression("data.user.list[0].trim()"))
        val get = assertIs<Expr.Get>(call.callee)
        assertEquals("trim", get.name)
        val index = assertIs<Expr.Index>(get.target)
        assertIs<Expr.Lit>(index.index)
        val inner = assertIs<Expr.Get>(index.target)
        assertEquals("list", inner.name)
    }

    @Test
    fun callArgumentsAreParsed() {
        val call = assertIs<Expr.Call>(parseExpression("f(1, 'a', true)"))
        assertEquals(3, call.args.size)
    }

    @Test
    fun objectAndArrayLiterals() {
        val obj = assertIs<Expr.Obj>(parseExpression("{ name: 'bala', tags: ['a'] }"))
        assertEquals(listOf("name", "tags"), obj.entries.map { it.first })
        assertIs<Expr.Arr>(obj.entries[1].second)
    }

    // ── 语法：if 是表达式 ────────────────────────────────────

    @Test
    fun ifIsExpressionWithOptionalElse() {
        val withElse = assertIs<Expr.If>(parseExpression("if (a > 1) 'big' else 'small'"))
        assertIs<Expr.Binary>(withElse.cond)
        assertIs<Expr.Lit>(withElse.thenExpr)
        assertIs<Expr.Lit>(withElse.elseExpr)

        val noElse = assertIs<Expr.If>(parseExpression("if (a) 1"))
        assertEquals(null, noElse.elseExpr)
    }

    @Test
    fun elseIfNestsAsExpression() {
        val expr = assertIs<Expr.If>(parseExpression("if (a) 1 else if (b) 2 else 3"))
        assertIs<Expr.If>(expr.elseExpr)
    }

    @Test
    fun blockBranchStaysBlock() {
        val expr = assertIs<Expr.If>(parseExpression("if (a) { x = 1 } else { x = 2 }"))
        assertIs<Expr.Block>(expr.thenExpr)
    }

    // ── 语法：语句 ───────────────────────────────────────────

    @Test
    fun valAndVarDeclarations() {
        val statements = parseStatements("val a = 1\nvar b = 2")
        val first = assertIs<Stmt.VarDecl>(statements[0])
        val second = assertIs<Stmt.VarDecl>(statements[1])
        assertEquals("a", first.name)
        assertTrue(!first.mutable)
        assertTrue(second.mutable)
    }

    @Test
    fun assignmentIsStatement() {
        val statements = parseStatements("data.user.name = '李四'")
        val assign = assertIs<Stmt.Assign>(statements.single())
        assertIs<Expr.Get>(assign.target)
    }

    @Test
    fun assigningToLiteralIsRejected() {
        val error = assertFailsWith<SyntaxError> { parseStatements("1 = 2") }
        assertTrue(error.message!!.contains("赋值左侧"))
    }

    @Test
    fun semicolonSeparatesStatementsInAttributeMode() {
        val statements = parseStatements("val x = 1; data.x = x", LexMode.XmlAttribute)
        assertEquals(2, statements.size)
    }

    @Test
    fun newlineSeparatesStatementsInScriptMode() {
        assertEquals(2, parseStatements("x\ny").size)
    }

    @Test
    fun newlineIsWhitespaceInAttributeMode() {
        // XML 属性里换行是空白 → `x - y` 是一个表达式
        val expr = assertIs<Expr.Binary>(parseExpression("x\n- y"))
        assertEquals(TokenKind.Minus, expr.op)
    }

    @Test
    fun binaryOperatorCanSpanLines() {
        val expr = assertIs<Expr.Binary>(parseStatements("val z = a +\n b").let {
            (it.single() as Stmt.VarDecl).init
        })
        assertEquals(TokenKind.Plus, expr.op)
    }

    @Test
    fun returnWithAndWithoutValue() {
        val withValue = assertIs<Stmt.Return>(parseStatements("return 1").single())
        assertIs<Expr.Lit>(withValue.value)
        val bare = assertIs<Stmt.Return>(parseStatements("return").single())
        assertEquals(null, bare.value)
    }

    // ── 语法：await 只在语句层且必须调用 ─────────────────────

    @Test
    fun awaitRequiresCall() {
        val statement = parseStatements("await http.get(url)").single()
        val await = assertIs<Expr.Await>(assertIs<Stmt.ExprStmt>(statement).expr)
        assertEquals("get", assertIs<Expr.Get>(await.call.callee).name)
    }

    @Test
    fun awaitCanBeTheRightHandSideOfADeclaration() {
        // 语句节点装不进 `VarDecl.init`，所以 await 必须是表达式（I-9）
        val decl = assertIs<Stmt.VarDecl>(parseStatements("val r = await http.get(url)").single())
        assertIs<Expr.Await>(decl.init)
    }

    @Test
    fun awaitOfNonCallIsRejected() {
        val name = assertFailsWith<SyntaxError> { parseStatements("await x") }
        assertTrue(name.message!!.contains("函数调用"))

        val arithmetic = assertFailsWith<SyntaxError> { parseStatements("await 1 + 2") }
        assertTrue(arithmetic.message!!.contains("函数调用"))
    }

    @Test
    fun awaitResultCannotBeUsedInExpression() {
        val error = assertFailsWith<SyntaxError> { parseStatements("await f(x) + 1") }
        assertTrue(error.message!!.contains("先赋给变量"))
    }

    // ── 语法：lambda ─────────────────────────────────────────

    @Test
    fun lambdaWithParameters() {
        val lambda = parseLambda("i -> action.pick(i)")
        assertEquals(listOf("i"), lambda.params)
        assertEquals(1, lambda.body.size)
    }

    @Test
    fun lambdaWithTwoParameters() {
        val lambda = parseLambda("a, b -> action.save(a, b)")
        assertEquals(listOf("a", "b"), lambda.params)
    }

    @Test
    fun lambdaWithoutParameters() {
        val lambda = parseLambda("action.click()")
        assertTrue(lambda.params.isEmpty())
        assertIs<Stmt.ExprStmt>(lambda.body.single())
    }

    @Test
    fun lambdaBodyCanHaveMultipleStatements() {
        val lambda = parseLambda("val x = 1; action.save(x)", LexMode.XmlAttribute)
        assertEquals(2, lambda.body.size)
    }

    // ── 错误定位 ─────────────────────────────────────────────

    @Test
    fun syntaxErrorCarriesSpan() {
        val error = assertFailsWith<SyntaxError> { parseExpression("1 + ") }
        val span = error.span
        assertTrue(span != null, "语法错误必须带位置")
        assertTrue(span!!.start >= 0)
    }

    @Test
    fun unterminatedStringIsReported() {
        assertFailsWith<SyntaxError> { Lexer("'abc").tokens() }
    }

    @Test
    fun objectLiteralRequiresKey() {
        assertFailsWith<SyntaxError> { parseExpression("{ 1: 2 }") }
    }

    private fun ScanResult.formOrNull(): AttrForm? = (this as? ScanResult.Ok)?.form

}
