package cn.changjiahong.composexmlform.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.ChangeBus
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataChange
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.HostObject
import cn.changjiahong.composexmlform.engine.value.MutableDataObject
import cn.changjiahong.composexmlform.engine.value.Path
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.WriteSource
import cn.changjiahong.composexmlform.engine.value.normalize
import cn.changjiahong.composexmlform.engine.value.readIn

/**
 * 业务数据根。
 *
 * 数据只有一种形态：`DataObject`。它不是门面包装 —— **引擎真正读写的就这一个对象**，
 * 所以「业务层写入」「`{{path}}` 双向绑定写回」「`changes()` 观察到的事件」看的是同一份状态，
 * 不需要事件总线去做同步。
 *
 * 与 `DataObject.of(map)` 的差别只有一处：这里挂了一条 [ChangeBus]，
 * 于是 `data.set(...)` 的写入能出现在 `changes()` 里。嵌套容器不挂总线 ——
 * 只有根能拼出完整路径（容器没有父指针）。
 */
fun dataOf(data: Map<String, Any?> = emptyMap()): DataObject {
    val root = MutableDataObject(channel = ChangeBus())
    for ((key, value) in data) root.setMember(key, normalize(value))
    return root
}

/** 从 JSON 文本建数据根。 */
fun dataOfJson(json: String, format: Json = Json): DataObject = dataOf(jsonToMap(json, format))

/**
 * 在 Compose 里建一个随调用点存活的数据根。
 *
 * key 是参数本身：同结构的 Map 不会重建，界面不会因为一次重组丢掉用户已经输入的内容；
 * 参数**真的变了**才换实例（此时运行时重建、输入重置，这是符合直觉的）。
 *
 * 注意 Map 会被深拷贝一次，之后改传入的 Map **不影响**界面 —— 要改数据请用返回的对象。
 */
@Composable
fun rememberData(data: Map<String, Any?> = emptyMap()): DataObject = remember(data) { dataOf(data) }

/** 同 [rememberData]，入参是 JSON 文本。 */
@Composable
fun rememberData(json: String): DataObject = remember(json) { dataOfJson(json) }

// ── 读 ────────────────────────────────────────────────────────

/**
 * 按路径读取，返回引擎内部值（对象 / 列表是活的容器）。
 *
 * 在 Composable 里直接调它就是响应式的 —— 它会读到 `MutableState`，读即订阅，
 * 粒度精确到被读的那个字段。路径不存在返回 [Absent]（不是 `null`）。
 */
fun DataObject.get(path: String): Value {
    val parsed = Path.parse(path) ?: return Absent
    var current: Value = this
    for (segment in parsed.segments) {
        if (current !is DataObject && current !is DataArray) return Absent
        current = readIn(current, segment)
    }
    return current
}

fun DataObject.getString(path: String, default: String = ""): String = when (val value = get(path)) {
    is String -> value
    is Long -> value.toString()
    is Double -> formatDouble(value)
    is Boolean -> value.toString()
    else -> default
}

fun DataObject.getLong(path: String, default: Long = 0L): Long = when (val value = get(path)) {
    is Long -> value
    is Double -> value.toLong()
    is String -> value.trim().toLongOrNull() ?: default
    else -> default
}

fun DataObject.getInt(path: String, default: Int = 0): Int =
    getLong(path, default.toLong()).toInt()

fun DataObject.getDouble(path: String, default: Double = 0.0): Double = when (val value = get(path)) {
    is Double -> value
    is Long -> value.toDouble()
    is String -> value.trim().toDoubleOrNull() ?: default
    else -> default
}

fun DataObject.getBoolean(path: String, default: Boolean = false): Boolean = when (val value = get(path)) {
    is Boolean -> value
    else -> default
}

/** 列表的普通副本；不是列表时返回空列表（读的地方多半在渲染路径上，不该抛）。 */
fun DataObject.getList(path: String): List<Value> =
    (get(path) as? DataArray)?.elements ?: emptyList()

/** 对象的普通副本；不是对象时返回空表。 */
fun DataObject.getMap(path: String): Map<String, Value> =
    (get(path) as? DataObject)?.snapshot() ?: emptyMap()

// ── 写 ────────────────────────────────────────────────────────

/**
 * 写入单个字段（细粒度）。返回数据是否真的发生变化 —— 同值写入不发通知、不触发重组。
 *
 * 中间层不存在时返回 `false` 而**不创建**中间层：自动补全会让「路径拼错」静默成功，
 * 那比报错难查得多。
 */
fun DataObject.set(path: String, value: Any?): Boolean {
    val normalized = normalize(value)
    // 同值写入不发通知：宿主普遍在 `onValueChange` 里无条件 `set`，
    // 不判等会让「每次按键都重组整页」变成默认行为
    val current = get(path)
    if (current !== Absent && equalValue(current, normalized)) return false
    return assign(path, normalized, WriteSource.Program)
}

/**
 * 增量合并：只改真正变化的字段，尽量复用已有容器，保住元素身份。
 *
 * - 两边都是对象 → 递归合并（输入框焦点、`Foreach` 元素身份都不会丢）
 * - 两边都是列表 → 按下标对齐递归；不足的追加，**多出来的保留不删**
 * - 其它 → 值不同才覆盖
 *
 * 要删字段、裁元素、或者确实要整体重建，用 [reset]。
 */
fun DataObject.patch(patch: Map<String, Any?>): Boolean {
    val source = normalize(patch) as? DataObject ?: return false
    return mergeInto(this, source)
}

fun DataObject.patchJson(json: String, format: Json = Json): Boolean = patch(jsonToMap(json, format))

/**
 * 整体替换（显式重建语义）：调用方明确表示「这是全新一份数据」。
 *
 * 原地改内容、不换容器实例，这样 `changes()` / `observe(...)` 的订阅依然有效。
 * 事件只发一条汇总（`path = ""`）。
 */
fun DataObject.reset(data: Map<String, Any?>) {
    val old = snapshot()
    for (key in keys()) removeMember(key)
    for ((key, value) in data) setMember(key, normalize(value))
    channel?.emit(DataChange("", old, snapshot(), WriteSource.Program))
}

// ── 订阅 ──────────────────────────────────────────────────────

/** 任意变更的聚合流：一条事件 = 一次写入操作，带旧值与来源。没有挂总线时是空流。 */
fun DataObject.changes(): Flow<DataChange> = (channel as? ChangeBus)?.changes ?: emptyFlow()

/**
 * 按路径精确订阅，给非 Compose 侧（ViewModel / 业务层 / 后台协程）用。
 *
 * 实现是 `snapshotFlow { get(path) }` —— **每次都重新走路径**，
 * 因此 `user` 被整体替换成新对象后会自动跟到新节点上；缓存某个 `MutableState` 的话，
 * 旧节点被丢弃后订阅会永久静默。
 */
fun DataObject.observe(path: String): Flow<Value> = snapshotFlow { get(path) }

/** 同 [observe]，但把值收敛成字符串（空值给 `default`）。 */
fun DataObject.observeString(path: String, default: String = ""): Flow<String> =
    observe(path).map { value ->
        when (value) {
            is String -> value
            is Long -> value.toString()
            is Double -> formatDouble(value)
            is Boolean -> value.toString()
            else -> default
        }
    }

// ── 列表句柄 ──────────────────────────────────────────────────

/**
 * 取一个列表的结构句柄（增删改）。
 *
 * 取句柄时**立刻校验**「这个路径是不是列表」—— 路径拼错当场报错，不会拖到操作那一刻。
 */
fun DataObject.list(path: String): DataList {
    val array = get(path) as? DataArray
        ?: throw IllegalArgumentException("路径 '$path' 不是列表，无法执行列表操作")
    return DataList(this, path, array)
}

/**
 * 列表结构句柄。
 *
 * 每次操作都发一条 `DataChange`（`old` / `new` 是列表的普通快照），
 * 与旧实现的语义一致：一次操作一条事件，宿主的脏检查不必猜。
 */
class DataList internal constructor(
    private val root: DataObject,
    private val path: String,
    private val array: DataArray,
) {

    val size: Int get() = array.size

    operator fun get(index: Int): Value = array.get(index)

    fun toList(): List<Value> = array.elements

    fun add(value: Any?) = mutate { array.append(normalize(value)) }

    fun insert(index: Int, value: Any?) = mutate { array.insert(index, normalize(value)) }

    fun removeAt(index: Int): Value {
        var removed: Value = Absent
        mutate { removed = array.removeAt(index) }
        return removed
    }

    fun set(index: Int, value: Any?) = mutate { array.set(index, normalize(value)) }

    fun clear() = mutate { while (array.size > 0) array.removeAt(array.size - 1) }

    /**
     * 一次操作一个事务：抛错（越界、冻结）会中止整个操作，不留半截修改，
     * 也不会推事件 —— `emit` 在 `block` 之后。
     */
    private inline fun mutate(block: () -> Unit) {
        val old = plain(array)
        block()
        root.channel?.emit(DataChange(path, old, plain(array), WriteSource.Program))
    }
}

// ── JSON ──────────────────────────────────────────────────────

/** 整份数据的 JSON 文本。`Function` 成员被跳过（代码不是数据）。 */
fun DataObject.toJson(pretty: Boolean = true): String {
    val element = jsonOf(snapshot())
    return if (pretty) PRETTY.encodeToString(JsonElement.serializer(), element)
    else Json.encodeToString(JsonElement.serializer(), element)
}

/** 解析 JSON 对象文本为普通 Map（不建容器，交给调用方决定要不要 `dataOf`）。 */
fun jsonToMap(json: String, format: Json = Json): Map<String, Any?> {
    val element = format.parseToJsonElement(json)
    require(element is JsonObject) { "根必须是 JSON 对象" }
    @Suppress("UNCHECKED_CAST")
    return valueOf(element) as Map<String, Any?>
}

/** JSON → 引擎值（`Map` / `List` 形态，由 `normalize` 收敛成容器）。 */
fun valueOf(element: JsonElement): Value = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        else -> element.booleanOrNull
            ?: element.longOrNull
            ?: element.content.toDoubleOrNull()
            ?: element.content
    }

    is JsonObject -> element.mapValues { valueOf(it.value) }
    is JsonArray -> element.map { valueOf(it) }
}

/** 引擎值 → JSON。只认规范形态；容器递归，`Function` 落成 `null`（与 `snapshot` 的口径一致）。 */
fun jsonOf(value: Value): JsonElement = when (value) {
    null, Absent -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to jsonOf(it.value) })
    is List<*> -> JsonArray(value.map { jsonOf(it) })
    is DataObject -> JsonObject(value.snapshot().mapValues { jsonOf(it.value) })
    is DataArray -> JsonArray(value.elements.map { jsonOf(it) })
    is Function -> JsonNull
    is HostObject -> JsonPrimitive(value.raw.toString())
    else -> JsonNull
}

// ── 内部 ──────────────────────────────────────────────────────

private val PRETTY: Json = Json { prettyPrint = true }

/** 整数值不显示 `.0` —— 与 `StringAdapter` 的显示规则一致（P-6）。 */
private fun formatDouble(value: Double): String =
    if (value % 1.0 == 0.0 && value >= -9.0e15 && value <= 9.0e15) value.toLong().toString()
    else value.toString()

/** 容器 → 普通结构（事件里的 `old` / `new` 用它，避免把活容器发出去）。 */
private fun plain(value: Value): Value = when (value) {
    is DataObject -> value.snapshot()
    is DataArray -> value.elements.map { plain(it) }
    else -> value
}

/** 递归合并。返回是否有字段真的变化。 */
private fun mergeInto(target: DataObject, patch: DataObject): Boolean {
    var changed = false
    for (key in patch.keys()) {
        val incoming = patch.member(key)
        val existing = target.member(key)
        val branch = when {
            incoming is DataObject && existing is DataObject -> mergeInto(existing, incoming)
            incoming is DataArray && existing is DataArray -> mergeList(existing, incoming)
            else -> {
                val same = existing != Absent && equalValue(existing, incoming)
                if (!same) target.setMember(key, incoming)
                !same
            }
        }
        if (branch) changed = true
    }
    return changed
}

private fun mergeList(target: DataArray, patch: DataArray): Boolean {
    var changed = false
    for (index in 0 until patch.size) {
        val incoming = patch.get(index)
        if (index >= target.size) {
            target.append(incoming)
            changed = true
            continue
        }
        val existing = target.get(index)
        val branch = when {
            incoming is DataObject && existing is DataObject -> mergeInto(existing, incoming)
            incoming is DataArray && existing is DataArray -> mergeList(existing, incoming)
            else -> {
                val same = equalValue(existing, incoming)
                if (!same) target.set(index, incoming)
                !same
            }
        }
        if (branch) changed = true
    }
    return changed
}

/** 结构相等：容器比内容，标量按值。`2` 与 `2.0` 视为相同（与脚本的 `==` 同口径）。 */
private fun equalValue(a: Value, b: Value): Boolean {
    if (a === b) return true
    if (a is Long && b is Double) return a.toDouble() == b
    if (a is Double && b is Long) return a == b.toDouble()
    if (a is DataObject && b is DataObject) {
        val keys = a.keys()
        if (keys.size != b.keys().size) return false
        return keys.all { equalValue(a.member(it), b.member(it)) }
    }
    if (a is DataArray && b is DataArray) {
        if (a.size != b.size) return false
        return (0 until a.size).all { equalValue(a.get(it), b.get(it)) }
    }
    return a == b
}
