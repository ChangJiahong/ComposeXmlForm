package cn.changjiahong.composexmlform.engine.syntax

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Span

/**
 * 一个属性值切分后的形态 —— 三种括号定稿（第 11 轮）：
 *
 * | 写法 | 形态 | 含义 |
 * | --- | --- | --- |
 * | `"raw"` | [Literal] | 字面量 |
 * | `{{path}}` | [Binding] | 数据绑定，**唯一可写回** |
 * | `{expr}` | [Code] | 闭包，求值 |
 * | `@{x -> …}` | [Lambda] | 函数引用 |
 *
 * 这里只切分、不求值、不判路径合法性 —— 那是 parse 层与 eval 的事。
 */
internal sealed interface AttrForm {

    data class Literal(val text: String) : AttrForm

    /** [body] 是 `{{` 与 `}}` 之间的原文，[span] 是它在属性值内的位置。 */
    data class Binding(val body: String, val span: Span) : AttrForm

    data class Code(val body: String, val span: Span) : AttrForm

    data class Lambda(val body: String, val span: Span) : AttrForm
}

internal sealed interface ScanResult {

    data class Ok(val form: AttrForm) : ScanResult

    data class Err(val code: Code, val message: String, val span: Span) : ScanResult
}

/**
 * 属性值切分器。
 *
 * 判断顺序是**最长匹配**（第 11 轮定的）：
 * ```
 * "\\{"  → 转义的字面量
 * "{{"   → Binding
 * "@{"   → Lambda
 * "{"    → Code
 * 其他    → Literal
 * ```
 *
 * 配对不能「找第一个 `}`」：嵌套块、字符串里的花括号都会骗过它。所以扫描器要跟踪
 * 深度与字符串状态 —— 而且必须与 [Lexer] 用同一套字符串/转义规则，否则会出现
 * 「属性切分与词法理解不一致」这种最难查的错误（D-6）。
 */
internal object AttrScanner {

    fun scan(raw: String): ScanResult {
        val first = raw.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return ScanResult.Ok(AttrForm.Literal(raw))

        // 转义的字面量：`\{abc\}` → `{abc}`
        if (raw.startsWith("\\{", first)) {
            return ScanResult.Ok(AttrForm.Literal(unescapeBraces(raw.substring(first))))
        }

        return when {
            raw.startsWith("{{", first) -> binding(raw, first)
            raw.startsWith("@{", first) -> lambda(raw, first)
            raw[first] == '{' -> code(raw, first)
            else -> ScanResult.Ok(AttrForm.Literal(raw))
        }
    }

    private fun binding(raw: String, open: Int): ScanResult {
        val bodyStart = open + 2
        val close = raw.indexOf("}}", bodyStart)
        if (close < 0) {
            return ScanResult.Err(
                Code.MalformedBraces,
                "绑定没有闭合：缺少 }}",
                spanAt(raw, open, raw.length),
            )
        }
        val body = raw.substring(bodyStart, close).trim()
        if (body.isEmpty()) {
            return ScanResult.Err(Code.EmptyBinding, "{{}} 里不能为空", spanAt(raw, open, close + 2))
        }
        return ScanResult.Ok(AttrForm.Binding(body, spanAt(raw, bodyStart, close)))
    }

    private fun code(raw: String, open: Int): ScanResult {
        val close = matchingBrace(raw, open)
            ?: return ScanResult.Err(
                Code.MalformedBraces,
                "代码块没有闭合：缺少 }",
                spanAt(raw, open, raw.length),
            )
        return ScanResult.Ok(AttrForm.Code(raw.substring(open + 1, close), spanAt(raw, open + 1, close)))
    }

    private fun lambda(raw: String, at: Int): ScanResult {
        val open = at + 1 // '@' 之后就是 '{'
        val close = matchingBrace(raw, open)
            ?: return ScanResult.Err(
                Code.MalformedBraces,
                "函数引用没有闭合：缺少 }",
                spanAt(raw, at, raw.length),
            )
        return ScanResult.Ok(AttrForm.Lambda(raw.substring(open + 1, close), spanAt(raw, open + 1, close)))
    }

    /** 返回与 [open] 处 `{` 配对的 `}` 下标。跟踪嵌套与字符串。 */
    private fun matchingBrace(raw: String, open: Int): Int? {
        var depth = 0
        var i = open
        var quote: Char? = null

        while (i < raw.length) {
            val c = raw[i]

            if (quote != null) {
                when {
                    c == '\\' && i + 1 < raw.length -> i++ // 跳过被转义的字符
                    c == quote -> quote = null
                }
                i++
                continue
            }

            when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                    if (depth < 0) return null
                }
            }
            i++
        }
        return null
    }

    private fun unescapeBraces(text: String): String =
        text.replace("\\{", "{").replace("\\}", "}")

    /** 属性值内相对偏移 → 行 / 列（行从 1 起）。调用方再加 `valueSpan` 的起点映射回原文。 */
    fun spanAt(text: String, start: Int, end: Int): Span {
        var line = 1
        var lineStart = 0
        for (i in 0 until start.coerceAtMost(text.length)) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return Span(start, end, line, start - lineStart + 1)
    }
}
