package cn.changjiahong.composexmlui.store

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * 可观察数据转换。
 *
 * 设计要点：
 * - `Map` → [ObservableMap]：**每个 key 一个 MutableState**，读取某个 key 只订阅该 key，
 *   因此写 `username` 不会让只读 `region` 的组件重组 —— 这是细粒度更新的基础。
 * - `List` → [ObservableList]：列表结构（长度/顺序）由 SnapshotStateList 统一管理，
 *   `Foreach` 订阅列表结构，元素内部的字段写入只影响对应元素。
 *
 * 注意：这些类型是实现细节，虽然 public（便于扩展组件读写），但不作为稳定契约。
 */
object ObservableData {

    /** 把普通数据结构转换成可观察结构；已可观察的对象原样返回（幂等）。 */
    fun wrap(value: Any?): Any? = when (value) {
        null -> null
        is ObservableMap, is ObservableList -> value
        is Map<*, *> -> {
            val map = LinkedHashMap<String, Any?>(value.size)
            value.forEach { (k, v) -> map[k?.toString() ?: "null"] = v }
            ObservableMap(map)
        }
        is List<*> -> ObservableList(value)
        else -> value
    }

    /** 把可观察结构还原成普通数据结构。 */
    fun unwrap(value: Any?): Any? = when (value) {
        is ObservableMap -> value.toPlain()
        is ObservableList -> value.toPlain()
        else -> value
    }

    /** 显示用字符串：null → 空串。 */
    fun asString(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        is Number -> value.toString()
        else -> unwrap(value).toString()
    }

    /** 条件判断用布尔值。 */
    fun asBoolean(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty() && !value.equals("false", ignoreCase = true) && value != "0"
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is ObservableList -> value.size > 0
        is ObservableMap -> value.keys.isNotEmpty()
        else -> true
    }
}

/**
 * key → MutableState 的映射容器：细粒度响应式的落点。
 */
class ObservableMap internal constructor(initial: Map<String, Any?>) {

    private val order = ArrayList<String>()
    private val states = HashMap<String, MutableState<Any?>>()

    init {
        initial.forEach { (key, value) -> put(key, value) }
    }

    val keys: List<String> get() = order.toList()

    operator fun get(key: String): Any? = stateFor(key).value

    fun contains(key: String): Boolean = states.containsKey(key)

    fun put(key: String, value: Any?) {
        if (!states.containsKey(key)) order.add(key)
        stateFor(key).value = ObservableData.wrap(value)
    }

    fun remove(key: String) {
        if (states.remove(key) != null) order.remove(key)
    }

    fun toPlain(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(order.size)
        order.forEach { key -> result[key] = ObservableData.unwrap(states[key]?.value) }
        return result
    }

    private fun stateFor(key: String): MutableState<Any?> =
        states.getOrPut(key) { mutableStateOf(null) }
}

/**
 * 可观察列表：结构由 SnapshotStateList 管理，元素本身仍是可观察结构。
 */
class ObservableList internal constructor(initial: List<Any?>) {

    private val items = mutableStateListOf<Any?>().apply {
        initial.forEach { item -> add(ObservableData.wrap(item)) }
    }

    val size: Int get() = items.size

    operator fun get(index: Int): Any? = items[index]

    fun set(index: Int, value: Any?) {
        items[index] = ObservableData.wrap(value)
    }

    fun add(value: Any?) {
        items.add(ObservableData.wrap(value))
    }

    fun addAll(values: List<Any?>) {
        values.forEach { items.add(ObservableData.wrap(it)) }
    }

    fun removeAt(index: Int) {
        items.removeAt(index)
    }

    fun clear() {
        items.clear()
    }

    fun toList(): List<Any?> = items.toList()

    fun toPlain(): List<Any?> = items.map { ObservableData.unwrap(it) }
}
