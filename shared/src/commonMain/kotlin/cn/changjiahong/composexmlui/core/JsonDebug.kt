package cn.changjiahong.composexmlui.core

/**
 * 极简 JSON 序列化，仅用于调试面板展示当前业务数据。
 * 正式业务如需 JSON，请在外围使用 kotlinx.serialization。
 */
object JsonDebug {

    fun encode(value: Any?): String = StringBuilder().also { write(value, 0, it) }.toString()

    private fun write(value: Any?, depth: Int, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append(quote(value))
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(number(value))
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                val entries = value.entries.toList()
                entries.forEachIndexed { index, entry ->
                    indent(depth + 1, sb)
                    sb.append(quote(entry.key?.toString() ?: "null")).append(": ")
                    write(entry.value, depth + 1, sb)
                    if (index != entries.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                indent(depth, sb)
                sb.append('}')
            }
            is Collection<*> -> {
                if (value.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                val items = value.toList()
                items.forEachIndexed { index, item ->
                    indent(depth + 1, sb)
                    write(item, depth + 1, sb)
                    if (index != items.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                indent(depth, sb)
                sb.append(']')
            }
            else -> sb.append(quote(value.toString()))
        }
    }

    private fun number(value: Number): String {
        if (value is Double) {
            return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        }
        if (value is Float) {
            return if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        }
        return value.toString()
    }

    private fun indent(depth: Int, sb: StringBuilder) {
        repeat(depth) { sb.append("  ") }
    }

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        text.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
