package cn.changjiahong.composexmlform.engine.parse

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.diag.SyntaxError
import cn.changjiahong.composexmlform.engine.node.AttrRef
import cn.changjiahong.composexmlform.engine.syntax.AttrForm
import cn.changjiahong.composexmlform.engine.syntax.AttrScanner
import cn.changjiahong.composexmlform.engine.syntax.Expr
import cn.changjiahong.composexmlform.engine.syntax.LexMode
import cn.changjiahong.composexmlform.engine.syntax.ScanResult
import cn.changjiahong.composexmlform.engine.syntax.parseLambda
import cn.changjiahong.composexmlform.engine.syntax.parseStatements
import cn.changjiahong.composexmlform.engine.value.Path

/**
 * 阶段 ③：属性原文 → [AttrRef]。
 *
 * 这一层只做**形态**的事：
 * - 切括号（[AttrScanner]，与词法器共用字符串/转义规则）；
 * - `{{…}}` 切路径、`{…}` 编译表达式、`@{…}` 编译 lambda；
 * - 按 [ScriptMode] 拒绝当前档位不允许的写法。
 *
 * 它**不知道** `data` 里有什么、**不知道**槽位声明的类型 —— 那分别是阶段 ④ 与 `AttrSet` 的事。
 * 分开的好处是：语法错误（少个括号）与语义错误（绑到只读对象）在诊断里清清楚楚是两类。
 *
 * 所有报错都映射回**原始 XML 的行列**（D-3）：脚本编译时只知道「属性值内第 N 列」，
 * 而使用者看的是整份 XML。
 */
internal class AttrTyper(
    private val diagnostics: MutableList<Diagnostic>,
    private val mode: ScriptMode,
) {

    /**
     * 定型一个属性。返回 `null` 表示已报错，调用方跳过它。
     *
     * 返回 `null` 而不是塞一个占位 `Literal("")`：错误值继续往下走会让**后续阶段再报一次**
     * 同一个问题（比如 `{{}}` 都没闭合，路径阶段又报一次「不是合法路径」）。
     */
    fun type(attr: XmlAttr, tag: String): AttrRef? {
        return when (val scanned = AttrScanner.scan(attr.value)) {
            is ScanResult.Err -> {
                report(scanned.code, scanned.message, rebase(scanned.span, attr.valueSpan, 0), tag, attr.name)
                null
            }

            is ScanResult.Ok -> form(scanned.form, attr, tag)
        }
    }

    private fun form(form: AttrForm, attr: XmlAttr, tag: String): AttrRef? = when (form) {
        is AttrForm.Literal -> AttrRef.Literal(form.text)

        is AttrForm.Binding -> {
            val path = Path.parse(form.body)
            if (path == null) {
                report(
                    Code.InvalidPath,
                    "『${form.body}』不是合法路径；绑定只能写标识符与数字下标（`data.user.name`、`list[0].id`）",
                    rebase(form.span, attr.valueSpan, 0),
                    tag,
                    attr.name,
                )
                null
            } else {
                AttrRef.Bind(path)
            }
        }

        is AttrForm.Code -> {
            val blockSpan = rebase(form.span, attr.valueSpan, 0)
            when {
                mode == ScriptMode.NoScript -> {
                    report(Code.ScriptNotAllowed, "当前档位不允许 {…} 代码块", blockSpan, tag, attr.name)
                    null
                }

                else -> compileCode(form.body, form.span.start, attr, tag, blockSpan)
            }
        }

        is AttrForm.Lambda -> {
            val blockSpan = rebase(form.span, attr.valueSpan, 0)
            when {
                mode == ScriptMode.NoScript -> {
                    report(Code.ScriptNotAllowed, "当前档位不允许 @{…} 函数引用", blockSpan, tag, attr.name)
                    null
                }

                else -> compileLambda(form.body, form.span.start, attr, tag, blockSpan)
            }
        }
    }

    private fun compileCode(
        body: String,
        bodyOffset: Int,
        attr: XmlAttr,
        tag: String,
        blockSpan: Span,
    ): AttrRef? {
        // `{}` 是**闭包**（第 11 轮），不是「只能放一个表达式」：
        // `{ data.x = 1 }`、`{ val t = f(); t + 1 }` 都要能写。
        // 统一按语句序列编译再包成块，单表达式就是只有一条 ExprStmt 的块。
        val statements = try {
            parseStatements(body, LexMode.XmlAttribute)
        } catch (error: SyntaxError) {
            report(
                Code.ScriptSyntax,
                error.message ?: "表达式语法错误",
                error.span?.let { rebase(it, attr.valueSpan, bodyOffset) } ?: blockSpan,
                tag,
                attr.name,
            )
            return null
        }

        val expr = Expr.Block(statements, Span.at(0, body.length))

        // ExpressionsOnly 下 `{}` 只读：不许赋值、不许声明、不许 await
        if (mode == ScriptMode.ExpressionsOnly && ScriptAudit.exprHasSideEffect(expr)) {
            report(
                Code.ScriptNotAllowed,
                "当前档位下 {…} 只能读；要写数据请让宿主开启 Full，或改用事件回调 @{…}",
                blockSpan,
                tag,
                attr.name,
            )
            return null
        }

        if (mode != ScriptMode.Full && ScriptAudit.exprHasAwait(expr)) {
            report(
                Code.ScriptNotAllowed,
                "当前档位不允许 await；挂起调用需要宿主开启 Full",
                blockSpan,
                tag,
                attr.name,
            )
            return null
        }

        return AttrRef.Code(expr)
    }

    private fun compileLambda(
        body: String,
        bodyOffset: Int,
        attr: XmlAttr,
        tag: String,
        blockSpan: Span,
    ): AttrRef? {
        val lambda = try {
            parseLambda(body)
        } catch (error: SyntaxError) {
            report(
                Code.ScriptSyntax,
                error.message ?: "函数体语法错误",
                error.span?.let { rebase(it, attr.valueSpan, bodyOffset) } ?: blockSpan,
                tag,
                attr.name,
            )
            return null
        }

        if (mode == ScriptMode.ExpressionsOnly && ScriptAudit.statementsHaveSideEffect(lambda.body)) {
            report(
                Code.ScriptNotAllowed,
                "当前档位下 @{…} 只能调用已注册的函数，不能赋值或声明变量",
                blockSpan,
                tag,
                attr.name,
            )
            return null
        }

        if (mode != ScriptMode.Full && ScriptAudit.containsAwait(lambda.body)) {
            report(
                Code.ScriptNotAllowed,
                "当前档位不允许 await；挂起调用需要宿主开启 Full",
                blockSpan,
                tag,
                attr.name,
            )
            return null
        }

        return AttrRef.Lambda(lambda)
    }

    private fun report(code: Code, message: String, span: Span, tag: String, attribute: String) {
        diagnostics += Diagnostic(Severity.Error, code, message, span, tag, attribute)
    }
}

/**
 * 把「属性值内部的区间」映射回「整份 XML 的区间」。
 *
 * 三级偏移：脚本内偏移 → 属性值内偏移（`inValue`）→ XML 内偏移（`valueSpan.start`）。
 * 行列要单独算：跨越换行时列号不再累加，直接取内层列号。
 */
internal fun rebase(inner: Span, valueSpan: Span, inValue: Int): Span {
    val start = valueSpan.start + inValue + inner.start
    val end = valueSpan.start + inValue + inner.end
    return if (inner.line <= 1) {
        Span(start, end, valueSpan.line, valueSpan.column + inValue + inner.column - 1)
    } else {
        Span(start, end, valueSpan.line + inner.line - 1, inner.column)
    }
}
