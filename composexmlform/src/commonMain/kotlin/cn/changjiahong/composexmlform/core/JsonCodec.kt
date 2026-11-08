package cn.changjiahong.composexmlform.core

import cn.changjiahong.composexmlform.store.ObservableData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON ↔ 引擎数据模型（`null` / `Boolean` / `Long|Double` / `String` / `List` / `Map`）的转换。
 *
 * 关键约定：**JsonElement 只是输入格式，永远不会进入数据树**。
 * 所有入口（构造、`merge`、`applyJson`、`set`）都先经过 [normalize] 归一化，
 * 这样 `asString` / 绑定求值 / Renderer 只需要处理 Kotlin 原生类型。
 *
 * 数字规则：整数 → `Long`，带小数点 → `Double`。这是 JSON 往返唯一稳定的映射。
 */
object JsonCodec {

    /** 序列化：默认 pretty（调试友好），`pretty = false` 输出紧凑单行（传输友好）。 */
    fun encode(value: Any?, pretty: Boolean = true): String =
        StringBuilder().also { write(ObservableData.unwrap(value), 0, pretty, it) }.toString()

    /** 解析 JSON 文本，根必须是对象。 */
    fun parse(text: String, format: Json = Json): Map<String, Any?> {
        val element = try {
            format.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            throw DataException("JSON 解析失败: ${e.message}")
        }
        return toData(element)
    }

    /** 把 JsonElement 转成数据 Map，根必须是对象。 */
    @Suppress("UNCHECKED_CAST")
    fun toData(element: JsonElement): Map<String, Any?> {
        if (element !is JsonObject) {
            throw DataException(
                "JSON 根节点必须是对象 {...}，当前是 ${element::class.simpleName}。" +
                    "数据按 key 索引，数组或标量无法作为根。",
            )
        }
        return elementToValue(element) as Map<String, Any?>
    }

    /** 归一化单个值：JsonElement → 原生类型，Map / List 递归归一化。 */
    fun normalize(value: Any?): Any? = when (value) {
        null -> null
        is JsonElement -> elementToValue(value)
        is Map<*, *> -> {
            val result = LinkedHashMap<String, Any?>(value.size)
            value.forEach { (key, item) -> result[key?.toString() ?: "null"] = normalize(item) }
            result
        }
        is List<*> -> value.map { normalize(it) }
        else -> value
    }

    /** 归一化整个 Map 根。 */
    fun normalizeMap(data: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(data.size)
        data.forEach { (key, value) -> result[key] = normalize(value) }
        return result
    }

    private fun elementToValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> {
            val result = LinkedHashMap<String, Any?>(element.size)
            element.forEach { (key, value) -> result[key] = elementToValue(value) }
            result
        }
        is JsonArray -> element.map { elementToValue(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
        }
    }

    private fun write(value: Any?, depth: Int, pretty: Boolean, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append(quote(value))
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(number(value))
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                val entries = value.entries.toList()
                entries.forEachIndexed { index, entry ->
                    if (pretty) newline(depth + 1, sb) else if (index > 0) sb.append(',')
                    sb.append(quote(entry.key?.toString() ?: "null")).append(if (pretty) ": " else ":")
                    write(entry.value, depth + 1, pretty, sb)
                    if (pretty && index != entries.lastIndex) sb.append(',')
                }
                if (pretty) newline(depth, sb)
                sb.append('}')
            }
            is Collection<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                val items = value.toList()
                items.forEachIndexed { index, item ->
                    if (pretty) newline(depth + 1, sb) else if (index > 0) sb.append(',')
                    write(item, depth + 1, pretty, sb)
                    if (pretty && index != items.lastIndex) sb.append(',')
                }
                if (pretty) newline(depth, sb)
                sb.append(']')
            }
            else -> sb.append(quote(value.toString()))
        }
    }

    private fun number(value: Number): String = when (value) {
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun newline(depth: Int, sb: StringBuilder) {
        sb.append('\n')
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
