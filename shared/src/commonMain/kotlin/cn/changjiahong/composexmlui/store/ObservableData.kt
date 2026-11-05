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

    /**
     * 结构比较：与 [unwrap] 之后的普通值配合使用（调用方负责先 unwrap）。
     *
     * 数字按数值比较（`1` 与 `1L` 视为相同），Map / List 递归比较。
     * 用于「写入是否真的改变了数据」的判断，避免同值写入触发无意义的重组与通知。
     */
    fun valuesEqual(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a == null || b == null -> false
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Map<*, *> && b is Map<*, *> ->
            a.size == b.size && a.all { (key, value) -> b.containsKey(key) && valuesEqual(value, b[key]) }
        a is List<*> && b is List<*> ->
            a.size == b.size && a.indices.all { index -> valuesEqual(a[index], b[index]) }
        else -> a == b
    }
}

/**
 * key → MutableState 的映射容器：细粒度响应式的落点。
 *
 * 生命周期约定（关系到订阅能否继续收到更新，改动前务必读一遍）：
 * - **实例身份必须稳定**：`BindableData.reset` 之类操作只能原地清空 / 回填，
 *   不能新建 [ObservableMap] 替换掉旧的，否则 `derivedStateOf` / `snapshotFlow`
 *   挂在旧 state 对象上的依赖链会 detached，表现为「数据变了但界面不动、订阅不再触发」。
 * - **`state` 对象一旦创建就不销毁**：[remove] 只把 key 从顺序表摘掉并把值置 null，
 *   保留 `MutableState` 对象，这样 `observe("那个key")` 的订阅者不会静默失联。
 */
class ObservableMap internal constructor(initial: Map<String, Any?>) {

    private val order = LinkedHashSet<String>()
    private val states = HashMap<String, MutableState<Any?>>()

    /**
     * 结构版本号：key 的增删会 +1。
     *
     * 存在的唯一原因：`toPlain()` 只读每个 key 的 state，读不到「多了一个 key」这件事。
     * 把版本号读进 `toPlain()`，`derivedStateOf { toPlain() }` 才能在新字段出现时重算。
     */
    private val structureVersion = mutableStateOf(0)

    init {
        initial.forEach { (key, value) -> put(key, value) }
    }

    val keys: List<String> get() = order.toList()

    operator fun get(key: String): Any? = stateFor(key).value

    fun contains(key: String): Boolean = key in order

    fun put(key: String, value: Any?) {
        val isNewKey = order.add(key)
        if (isNewKey) structureVersion.value++
        stateFor(key).value = ObservableData.wrap(value)
    }

    /**
     * 摘除字段：从顺序表移除并把值置 null，但**保留 state 对象**。
     * 若直接丢弃 state，已建立的订阅会永久静默（见类注释）。
     */
    fun remove(key: String) {
        if (order.remove(key)) {
            states[key]?.value = null
            structureVersion.value++
        }
    }

    /**
     * 原地整体替换内容：保留仍在使用的 key 的 state 对象，保证已有订阅继续有效。
     * 只用于 `reset` 这类显式语义，不要拿它做增量更新（增量走 `put` / 递归 merge）。
     */
    fun resetTo(entries: Map<String, Any?>) {
        order.filter { it !in entries }.forEach { key ->
            order.remove(key)
            states[key]?.value = null
            structureVersion.value++
        }
        entries.forEach { (key, value) -> put(key, value) }
    }

    fun toPlain(): Map<String, Any?> {
        structureVersion.value // 建立「结构变化」依赖，供 derivedStateOf 使用
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

    fun insert(index: Int, value: Any?) {
        items.add(index, ObservableData.wrap(value))
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
