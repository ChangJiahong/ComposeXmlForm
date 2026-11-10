package cn.changjiahong.composexmlform.engine.diag

/**
 * 一条诊断。
 *
 * 定位信息分两层：[tag] / [attribute] 是「哪个标签的哪个属性」，[span] 是「哪个字符」。
 * 两层都要有 —— 只看 span 的数字对人无用，只看 tag 又定位不到属性值内部的语法错误。
 */
class Diagnostic(
    val severity: Severity,
    val code: Code,
    val message: String,
    val span: Span? = null,
    val tag: String? = null,
    val attribute: String? = null,
    /** 「你是不是想写 fontSize」这类修法建议。 */
    val hint: String? = null,
) {

    val isError: Boolean get() = severity == Severity.Error

    /**
     * `3:5 <Text> 属性 'value' — 期望表达式，遇到 '}'` + 源码片段。
     *
     * [source] 传入完整原文（或被解析的那段属性值）时，会附上定位行与 `^` 标记。
     */
    fun format(source: String? = null): String = buildString {
        span?.let { if (it.line > 0) append(it.locate()).append(' ') }
        tag?.let { append('<').append(it).append('>') }
        attribute?.let { append(" 属性 '").append(it).append('\'') }
        if (isNotEmpty()) append(" — ")
        append(message)
        hint?.let { append("（").append(it).append('）') }
        append(" [").append(severity).append('/').append(code).append(']')
        if (source != null && span != null && span.line > 0) {
            append('\n')
            append(snippet(source, span))
        }
    }

    override fun toString(): String = format()

    companion object {
        /**
         * 取 [span] 所在行，并在其下一行标出区间。
         *
         * 只做单行片段：跨行错误的 `^` 标记在终端里没有意义，而 XML 属性值 99% 是单行。
         */
        fun snippet(source: String, span: Span): String {
            val lineStart = source.lastIndexOf('\n', (span.start - 1).coerceAtLeast(0)).let {
                if (it < 0) 0 else it + 1
            }
            val lineEnd = source.indexOf('\n', span.start).let { if (it < 0) source.length else it }
            val text = source.substring(lineStart, lineEnd)
            val caretAt = (span.start - lineStart).coerceIn(0, text.length)
            val width = span.length.coerceAtLeast(1).coerceAtMost(text.length - caretAt).coerceAtLeast(1)
            return buildString {
                append("    ").append(text).append('\n')
                append("    ")
                repeat(caretAt) { append(' ') }
                repeat(width) { append('^') }
            }
        }
    }
}

// ── 收集辅助 ──────────────────────────────────────────────────
// 解析期是「收集全部诊断」而不是 fail-fast（D-4），所以到处都要往同一个 list 里塞。

fun MutableList<Diagnostic>.error(
    code: Code,
    message: String,
    span: Span? = null,
    tag: String? = null,
    attribute: String? = null,
    hint: String? = null,
) {
    add(Diagnostic(Severity.Error, code, message, span, tag, attribute, hint))
}

fun MutableList<Diagnostic>.warn(
    code: Code,
    message: String,
    span: Span? = null,
    tag: String? = null,
    attribute: String? = null,
    hint: String? = null,
) {
    add(Diagnostic(Severity.Warning, code, message, span, tag, attribute, hint))
}

/** 有任何 `Error` 级诊断。 */
fun List<Diagnostic>.hasErrors(): Boolean = any { it.isError }
