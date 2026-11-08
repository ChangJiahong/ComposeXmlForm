package cn.changjiahong.composexmlform.core

/**
 * 引擎统一异常。所有解析期、构建期、渲染期、运行时错误都通过它抛出，
 * 保证错误信息中尽可能带上「标签 / 属性 / 行号 / 原因」四要素。
 *
 * [diagnostics] 只有一种情况非空：`ParseMode.Strict` 收集完全部错误后统一抛出时，
 * 异常里带着完整列表 —— 否则调用方为了拿到「剩下的错」还得重新解析一遍。
 */
class XmlUiException(
    val reason: String,
    val tag: String? = null,
    val attribute: String? = null,
    val line: Int = 0,
    val code: Code = Code.Structure,
    val diagnostics: List<Diagnostic> = emptyList(),
) : Exception(format(reason, tag, attribute, line)) {

    /** 在缺少行号时补上行号（用于工厂内部抛出的异常向上补充上下文）。 */
    fun withLine(fallbackLine: Int): XmlUiException =
        if (line > 0 || fallbackLine <= 0) this
        else XmlUiException(reason, tag, attribute, fallbackLine, code, diagnostics)

    fun withTag(fallbackTag: String, fallbackLine: Int): XmlUiException =
        XmlUiException(
            reason = reason,
            tag = tag ?: fallbackTag,
            attribute = attribute,
            line = if (line > 0) line else fallbackLine,
            code = code,
            diagnostics = diagnostics,
        )
}

private fun format(reason: String, tag: String?, attribute: String?, line: Int): String {
    val prefix = buildString {
        if (line > 0) append("第 ").append(line).append(" 行: ")
        if (tag != null) append('<').append(tag).append('>')
        if (attribute != null) append(" 属性 '").append(attribute).append('\'')
    }
    return if (prefix.isEmpty()) "ComposeXmlUI: $reason" else "ComposeXmlUI: $prefix — $reason"
}

/** 便捷抛出工具。 */
fun xmlFail(
    reason: String,
    tag: String? = null,
    attribute: String? = null,
    line: Int = 0,
): Nothing = throw XmlUiException(reason, tag, attribute, line)

/**
 * 数据层异常：`BindableData` 的路径 / JSON / 列表操作错误。
 *
 * 与 [XmlUiException] 分开，是因为这些错误与 XML 无关（由调用方在运行时触发），
 * 调用方可以分别捕获。
 */
class DataException(reason: String) : Exception("ComposeXmlUI data: $reason")
