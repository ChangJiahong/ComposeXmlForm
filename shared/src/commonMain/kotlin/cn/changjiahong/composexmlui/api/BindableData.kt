package cn.changjiahong.composexmlui.api

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import cn.changjiahong.composexmlui.binding.ValuePath
import cn.changjiahong.composexmlui.core.DataException
import cn.changjiahong.composexmlui.core.JsonCodec
import cn.changjiahong.composexmlui.store.CONFIG_KEY
import cn.changjiahong.composexmlui.store.ObservableData
import cn.changjiahong.composexmlui.store.ObservableList
import cn.changjiahong.composexmlui.store.ObservableMap
import cn.changjiahong.composexmlui.store.readSegment
import cn.changjiahong.composexmlui.store.writeInto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 业务数据的门面对象：**由调用方持有，界面只是它的一个视图**。
 *
 * 定位（重要）：这里不存第二份数据。内部就是引擎真正在读写的 [ObservableMap]，
 * 所以「业务层写入」「TextField 双向绑定写回」「表单校验读取」看的都是同一份状态，
 * 不需要额外的事件总线去做同步。
 *
 * 三种消费方式，各管一段：
 * - [get] / [getString] 等：**在 Composable 里直接读就是响应式的**（Compose 读 state 即订阅），
 *   粒度精确到被读的那个字段。绝大多数场景用这个。
 * - [asState]：把整份数据当成一个「值」交给下游 —— 脏检查、整体提交、调试面板。
 *   注意它读全表，不要放在 `DynamicLayout` 根部的热路径上。
 * - [observe]：给非 Compose 侧（ViewModel / 业务层 / 后台协程）用，按路径精确订阅。
 *
 * 线程模型与普通 state 一致：**写操作请在主线程**；从后台线程写需要自己包
 * `Snapshot.withMutableSnapshot { }`。内部的多字段写入（[merge] / [reset]）已经包好，
 * 保证一次事务一次通知，订阅者不会看到「改了一半」的中间态。
 *
 * 路径语义：相对**业务数据根**。`config.*` 属于只读配置，不在这里（读写都会抛 [DataException]）；
 * `Foreach` 的 `item` / `index` 属于局部作用域，也不在这里 —— 要用 `observe("contacts[0].name")`。
 */
class BindableData(data: Map<String, Any?>) {

    /** 引擎直接读写的数据根；与 `RootStore` 持有的是同一个实例。 */
    internal val map: ObservableMap = ObservableMap(JsonCodec.normalizeMap(data))

    /**
     * 整表快照状态。缓存在字段上而不是每次新建，否则 `State` 身份每次不同，
     * 下游 `remember` / `LaunchedEffect` 会反复失效。
     */
    private val snapshotState: State<Map<String, Any?>> by lazy { derivedStateOf { map.toPlain() } }

    companion object {

        /** 从 JsonElement 构造；根必须是对象。 */
        fun ofJson(element: JsonElement): BindableData = BindableData(JsonCodec.toData(element))

        /** 从 JSON 文本构造；`format` 可配置 `ignoreUnknownKeys` / `explicitNulls` 等策略。 */
        fun ofJson(json: String, format: Json = Json): BindableData = BindableData(JsonCodec.parse(json, format))
    }

    // ---------------- 读 ----------------

    /** 顶层字段名。 */
    val keys: List<String> get() = map.keys

    /** 按路径读取，返回引擎内部值（对象 / 列表可能是可观察包装）。 */
    fun get(path: String): Any? = read(ValuePath.parse(path))

    fun getString(path: String, default: String = ""): String {
        val value = get(path) ?: return default
        return ObservableData.asString(value)
    }

    fun getInt(path: String, default: Int = 0): Int = when (val value = get(path)) {
        null -> default
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: default
        else -> default
    }

    fun getLong(path: String, default: Long = 0L): Long = when (val value = get(path)) {
        null -> default
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: default
        else -> default
    }

    fun getBoolean(path: String, default: Boolean = false): Boolean {
        val value = get(path) ?: return default
        return ObservableData.asBoolean(value)
    }

    /** 读取列表的普通副本；不是列表时抛 [DataException]。 */
    fun getList(path: String): List<Any?> =
        ObservableData.unwrap(get(path)) as? List<Any?>
            ?: throw DataException("路径 '$path' 不是列表")

    /** 读取对象的普通副本；不是对象时抛 [DataException]。 */
    @Suppress("UNCHECKED_CAST")
    fun getMap(path: String): Map<String, Any?> =
        ObservableData.unwrap(get(path)) as? Map<String, Any?>
            ?: throw DataException("路径 '$path' 不是对象")

    /** 当前业务数据的普通副本（不含 config 与局部变量）。非响应式，仅取值。 */
    fun snapshot(): Map<String, Any?> = map.toPlain()

    fun getJson(pretty: Boolean = true): String = JsonCodec.encode(snapshot(), pretty)

    // ---------------- 订阅 ----------------

    /**
     * 整表快照的响应式状态。内容变化才下发（`derivedStateOf` 默认结构相等）。
     *
     * 适合：脏检查、整体提交、调试展示、传给非 Composable 的下游。
     * 不适合：逐字段读取（用 [get]）或高频热路径（会 O(n) 重建快照）。
     */
    fun asState(): State<Map<String, Any?>> = snapshotState

    /**
     * 按路径精确订阅，给非 Compose 侧使用。
     *
     * 实现是 `snapshotFlow { read(path) }` —— **每次重新解析路径**，因此
     * `user` 被整体替换成新对象后会自动跟到新节点上；若缓存了某个 `MutableState`，
     * 旧节点被丢弃后订阅会永久静默。
     */
    fun observe(path: String): Flow<Any?> {
        val valuePath = ValuePath.parse(path)
        return snapshotFlow { ObservableData.unwrap(read(valuePath)) }
    }

    /**
     * 按业务主键追踪列表里的某一个元素。
     *
     * 与 `observe("contacts[0].name")` 的区别：后者跟的是**下标**，列表插入 / 删除 / 排序后
     * 会指向另一个元素；这里跟的是 `keyField == keyValue` 的那个元素，位置怎么变都跟得住。
     *
     * 发出的是该元素的普通 Map 快照（元素内部任意字段变化都会重新发出）；元素不存在
     * （被删除、或还没插入）时发出 `null`。元素内容和位置都没变时不会重复发出。
     *
     * 已知代价：每次重算都会遍历整个列表、读取每个元素的 [keyField] —— 这是「后来插入的
     * 元素变成目标」能被发现的必要条件。列表很大且更新极频繁时，改用 [observe] 订阅整个
     * 列表再自行过滤更划算。
     *
     * 主键重复时取**第一个**匹配项。列表里只有对象元素参与匹配，标量元素被跳过。
     *
     * @param listPath 列表路径，如 `contacts`
     * @param keyField 元素内作为主键的字段名，支持 `meta.id` 这种嵌套写法
     * @param keyValue 主键值，按值比较（`1` 与 `1L` 视为相同）
     * @throws DataException [listPath] 当前不是列表时（路径写错立刻报错，不静默发 null）
     */
    fun observeElement(
        listPath: String,
        keyField: String,
        keyValue: Any?,
    ): Flow<Map<String, Any?>?> {
        val listPathParsed = ValuePath.parse(listPath)
        val keyPath = ValuePath.parse(keyField)
        listAt(listPath)
        val target = JsonCodec.normalize(keyValue)
        return snapshotFlow {
            val list = read(listPathParsed) as? ObservableList ?: return@snapshotFlow null
            var found: ObservableMap? = null
            for (index in 0 until list.size) {
                val element = list[index] as? ObservableMap ?: continue
                // 每个元素的 key 都要读一遍：否则「后面插入的元素变成目标」不会触发重算
                val key = ObservableData.unwrap(readFrom(element, keyPath))
                if (found == null && ObservableData.valuesEqual(key, target)) found = element
            }
            // toPlain 会读元素全部字段的 state，因此元素内部任何修改都能触发重算
            found?.toPlain()
        }
    }

    // ---------------- 写 ----------------

    /**
     * 写入单个字段（细粒度）。返回数据是否真的发生变化 —— 同值写入不发通知、不触发重组。
     */
    fun set(path: String, value: Any?): Boolean {
        val valuePath = ValuePath.parse(path)
        val normalized = JsonCodec.normalize(value)
        if (ObservableData.valuesEqual(ObservableData.unwrap(read(valuePath)), normalized)) return false
        mutate { writeInto(map, valuePath, normalized) }
        return true
    }

    /**
     * 增量合并：只改真正变化的字段，尽量复用已有的对象 / 列表容器，保住元素身份。
     *
     * 合并规则：
     * - 两边都是对象 → 递归合并（`TextField` 焦点、`Foreach` 元素身份都不会丢）
     * - 两边都是列表 → 按下标对齐，逐项递归；不足的部分追加，**多出来的保留不删**
     * - 其它情况 → 值不同才覆盖
     *
     * 需要删除字段 / 裁掉列表元素、或者确实要整体重建时，用 [reset]。
     *
     * @return 是否有字段真的变化
     */
    fun merge(patch: Map<String, Any?>): Boolean {
        val normalized = JsonCodec.normalizeMap(patch)
        var changed = false
        mutate { changed = mergeInto(map, normalized) }
        return changed
    }

    /** 同 [merge]，来源是 JsonElement（根必须是对象）。 */
    fun applyJson(element: JsonElement): Boolean = merge(JsonCodec.toData(element))

    /** 同 [merge]，来源是 JSON 文本。 */
    fun applyJson(json: String, format: Json = Json): Boolean = merge(JsonCodec.parse(json, format))

    /**
     * 整体替换（显式重建语义）：调用方明确表示「这是全新一份数据」。
     *
     * 原地替换内容，不新建容器实例 —— 这样已有的 `asState` / `observe` 订阅依然有效。
     */
    fun reset(data: Map<String, Any?>) {
        val normalized = JsonCodec.normalizeMap(data)
        mutate { map.resetTo(normalized) }
    }

    /** 同 [reset]，来源是 JsonElement。 */
    fun reset(element: JsonElement) = reset(JsonCodec.toData(element))

    /** 列表末尾追加一项（原地操作，已有元素身份不变）。 */
    fun appendToList(path: String, value: Any?) {
        val list = listAt(path)
        mutate { list.add(JsonCodec.normalize(value)) }
    }

    /** 列表指定位置插入一项。 */
    fun insertListAt(path: String, index: Int, value: Any?) {
        val list = listAt(path)
        if (index !in 0..list.size) {
            throw DataException("insertListAt: 下标 $index 越界（列表长度 ${list.size}）")
        }
        mutate { list.insert(index, JsonCodec.normalize(value)) }
    }

    /** 移除列表指定位置（原地操作，其余元素身份不变）。 */
    fun removeListAt(path: String, index: Int) {
        val list = listAt(path)
        if (index !in 0 until list.size) {
            throw DataException("removeListAt: 下标 $index 越界（列表长度 ${list.size}）")
        }
        mutate { list.removeAt(index) }
    }

    /** 整体替换列表内容（元素身份会重建，慎用于正在输入的列表）。 */
    fun replaceList(path: String, values: List<Any?>) {
        val list = listAt(path)
        mutate {
            list.clear()
            list.addAll(values)
        }
    }

    override fun toString(): String = "BindableData(keys=${map.keys})"

    // ---------------- 内部 ----------------

    private fun read(path: ValuePath): Any? {
        if (path.first == CONFIG_KEY) {
            throw DataException(
                "路径 '${path.raw}' 指向只读配置 config，BindableData 只管业务数据。" +
                    "配置请在 XML 里用 {{config.xxx}} 读取。",
            )
        }
        return readFrom(map, path)
    }

    /** 从任意起点沿路径取值；[readFrom] 不检查 config，供元素内部取值复用。 */
    private fun readFrom(start: Any?, path: ValuePath): Any? {
        var current = start
        path.segments.forEach { segment -> current = readSegment(current, segment) }
        return current
    }

    private fun listAt(path: String): ObservableList =
        get(path) as? ObservableList
            ?: throw DataException("路径 '$path' 不是列表，无法执行列表操作")

    @Suppress("UNCHECKED_CAST")
    private fun mergeInto(target: ObservableMap, patch: Map<String, Any?>): Boolean {
        var changed = false
        patch.forEach { (key, value) ->
            val existing = if (target.contains(key)) target[key] else null
            val branchChanged = when {
                value is Map<*, *> && existing is ObservableMap ->
                    mergeInto(existing, value as Map<String, Any?>)
                value is List<*> && existing is ObservableList ->
                    mergeList(existing, value)
                else -> {
                    val same = ObservableData.valuesEqual(ObservableData.unwrap(existing), value)
                    if (!same) target.put(key, value)
                    !same
                }
            }
            if (branchChanged) changed = true
        }
        return changed
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeList(target: ObservableList, patch: List<*>): Boolean {
        var changed = false
        patch.forEachIndexed { index, raw ->
            val value = JsonCodec.normalize(raw)
            if (index >= target.size) {
                target.add(value)
                changed = true
                return@forEachIndexed
            }
            val existing = target[index]
            val branchChanged = when {
                value is Map<*, *> && existing is ObservableMap ->
                    mergeInto(existing, value as Map<String, Any?>)
                value is List<*> && existing is ObservableList ->
                    mergeList(existing, value)
                else -> {
                    val same = ObservableData.valuesEqual(ObservableData.unwrap(existing), value)
                    if (!same) target.set(index, value)
                    !same
                }
            }
            if (branchChanged) changed = true
        }
        return changed
    }

    /**
     * 包一层可变快照：多字段写入合并为一次 apply，订阅者看到的是原子结果。
     *
     * apply 之后主动 `sendApplyNotifications()`：`snapshotFlow` 依赖 apply 通知来感知变化，
     * 而这个通知平时由 Compose 的帧循环发出。外部业务代码（ViewModel / 后台协程）在没有帧循环时
     * 也应当能收到 [observe] 的回调，所以这里补一次（无待处理变化时是空操作，成本可忽略）。
     *
     * 用 [mutating] 而不是 `Snapshot.current is MutableSnapshot` 判断重入：
     * 全局快照本身就是 `MutableSnapshot` 的子类，那个判断永远成立，会把快照包裹直接短路掉。
     */
    private fun mutate(block: () -> Unit) {
        if (mutating) {
            block()
            return
        }
        mutating = true
        try {
            Snapshot.withMutableSnapshot(block)
            Snapshot.sendApplyNotifications()
        } finally {
            mutating = false
        }
    }

    private var mutating = false
}
