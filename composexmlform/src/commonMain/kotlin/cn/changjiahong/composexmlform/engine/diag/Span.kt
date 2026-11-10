package cn.changjiahong.composexmlform.engine.diag

/**
 * 源码区间。`start` / `end` 是**相对所属源码起点**的字符偏移，`line` / `column` 从 1 开始、用于人读。
 *
 * 为什么要 offset 而不只是行号：属性值里的脚本错误要定位到「属性值内部第 N 列」（D-3）。
 * 词法器记录的偏移加上属性值的 `valueSpan.start`，才能映射回原始 XML。
 */
data class Span(
    val start: Int,
    val end: Int,
    val line: Int,
    val column: Int,
) {

    val length: Int get() = end - start

    /** 覆盖两段的最小跨度，取起点较早者的行列。 */
    fun merge(other: Span): Span = if (start <= other.start) {
        Span(start, maxOf(end, other.end), line, column)
    } else {
        Span(other.start, maxOf(end, other.end), other.line, other.column)
    }

    /** 把本区间整体右移 [delta] 个字符（属性值内偏移 → XML 全局偏移）。 */
    fun shift(delta: Int): Span = copy(start = start + delta, end = end + delta)

    /** 带行列的简写，用于错误消息前缀。 */
    fun locate(): String = if (line > 0) "$line:$column" else "offset $start"

    companion object {
        /** 未知位置。`line == 0` 表示「没有源码可指」。 */
        val None = Span(0, 0, 0, 0)

        /** 词法器内部用：已知偏移，尚未换行统计时先填行 1 列 1。 */
        fun at(start: Int, end: Int = start): Span = Span(start, end, 1, 1)
    }
}
