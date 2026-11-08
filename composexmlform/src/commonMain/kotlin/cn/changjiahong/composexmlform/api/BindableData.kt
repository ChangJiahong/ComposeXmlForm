package cn.changjiahong.composexmlform.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.core.DataException
import cn.changjiahong.composexmlform.core.JsonCodec
import cn.changjiahong.composexmlform.store.ObservableData
import cn.changjiahong.composexmlform.store.ObservableList
import cn.changjiahong.composexmlform.store.ObservableMap
import cn.changjiahong.composexmlform.store.WriteSource
import cn.changjiahong.composexmlform.store.readSegment
import cn.changjiahong.composexmlform.store.writeInto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 业务数据的门面对象：**由调用方持有，界面只是它的一个视图**。
 *
 * 定位（重要）：这里不存第二份数据。内部就是引擎真正在读写的 [ObservableMap]，
 * 所以「业务层写入」「TextField 双向绑定写回」「表单校验读取」看的都是同一份状态，
 * 不需要额外的事件总线去做同步。
 *
 * 构造只有三个入口（[of] / [ofJson]），在 Compose 里写界面时用 [rememberBindableData]：
 * ```
 * val data = rememberBindableData(mapOf("name" to "张三"))
 * DynamicLayout(xml = xml, data = data)
 * ```
 *
 * 三种消费方式，各管一段：
 * - [get] / [getString] 等：**在 Composable 里直接读就是响应式的**（Compose 读 state 即订阅），
 *   粒度精确到被读的那个字段。绝大多数场景用这个。
 * - [asState]：把整份数据当成一个「值」交给下游 —— 脏检查、整体提交、调试面板。
 *   注意它读全表，不要放在 `DynamicLayout` 根部的热路径上。
 * - [observe] / [changes]：给非 Compose 侧（ViewModel / 业务层 / 后台协程）用。
 *   [observe] 按路径精确订阅；[changes] 是「任意变更」的聚合流，且带旧值与来源。
 *
 * 线程模型与普通 state 一致：**写操作请在主线程**；从后台线程写需要自己包
 * `Snapshot.withMutableSnapshot { }`。内部的多字段写入（[merge] / [reset]）已经包好，
 * 保证一次事务一次通知，订阅者不会看到「改了一半」的中间态。
 *
 * 路径语义：相对**业务数据根**。没有 `config` 这个路径 ——
 * `config` 是解析期资源，`{{config.x}}` 在解析时已经变成常量。
 */
class BindableData private constructor(internal val map: ObservableMap) {

    /**
     * 整表快照状态。缓存在字段上而不是每次新建，否则 `State` 身份每次不同，
     * 下游 `remember` / `LaunchedEffect` 会反复失效。
     */
    private val snapshotState: State<Map<String, Any?>> by lazy { derivedStateOf { map.toPlain() } }

    /**
     * 变更聚合流。`extraBufferCapacity` 只有 64：宿主应当**立即消费**或转成自己的队列，
     * 不应把它当可靠消息队列（超出的部分会被丢弃，数据本身不受影响）。
     */
    private val changesFlow = MutableSharedFlow<DataChange>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 注入给 [cn.changjiahong.composexmlform.store.RootStore] 的写入回调。
     *
     * 双向绑定不经过本类的方法（它直接写 `ObservableMap`），要让 [changes] 看得见，
     * 只能由写入点回调进来。`RootStore` 因此只承诺「一次写入操作一条事件」。
     */
    internal val changeListener: (ValuePath, Any?, Any?, WriteSource) -> Unit =
        { path, old, new, source -> publish(DataChange(path.raw, old, new, source.toChangeSource())) }

    companion object {

        /** 从普通数据结构构造。内部会做一次归一化深拷贝，之后改源 Map 不影响这里。 */
        fun of(data: Map<String, Any?>): BindableData = BindableData(ObservableMap(JsonCodec.normalizeMap(data)))

        /** 从 JsonElement 构造；根必须是对象。 */
        fun ofJson(element: JsonElement): BindableData = BindableData(ObservableMap(JsonCodec.toData(element)))

        /** 从 JSON 文本构造；`format` 可配置 `ignoreUnknownKeys` / `explicitNulls` 等策略。 */
        fun ofJson(json: String, format: Json = Json): BindableData =
            BindableData(ObservableMap(JsonCodec.parse(json, format)))
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

    /** 当前业务数据的普通副本。非响应式，仅取值。 */
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
     * 任意变更的聚合流：一条事件 = 一次写入操作。
     *
     * 与 [observe] 的分工：[observe] 盯一条路径、只给新值；这里给「谁改了哪个字段、
     * 从什么变成什么」，用于脏检查、离开拦截、增量提交。
     *
     * 覆盖范围：本类的全部写入方法 + 渲染层双向绑定写回（来源为
     * [DataChange.Source.UserBinding]）。**不覆盖**第三方直接操作内部容器的写入。
     */
    fun changes(): Flow<DataChange> = changesFlow

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
        val old = ObservableData.unwrap(read(valuePath))
        if (ObservableData.valuesEqual(old, normalized)) return false
        mutate { writeInto(map, valuePath, normalized) }
        publish(DataChange(path, old, normalized, DataChange.Source.Program))
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
        val old = map.toPlain()
        mutate { changed = mergeInto(map, normalized) }
        if (changed) publish(DataChange("", old, map.toPlain(), DataChange.Source.Program))
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
     * 事件只发一条汇总（`path = ""`），不做逐字段展开。
     */
    fun reset(data: Map<String, Any?>) {
        val normalized = JsonCodec.normalizeMap(data)
        val old = map.toPlain()
        mutate { map.resetTo(normalized) }
        publish(DataChange("", old, map.toPlain(), DataChange.Source.Program))
    }

    /** 同 [reset]，来源是 JsonElement。 */
    fun reset(element: JsonElement) = reset(JsonCodec.toData(element))

    /**
     * 取一个列表的结构句柄（增删改）。
     *
     * 取句柄时立刻校验「这个路径是不是列表」——路径拼错当场报 [DataException]，
     * 不会拖到操作列表那一刻。
     */
    fun list(path: String): DataList {
        val valuePath = ValuePath.parse(path)
        if (read(valuePath) !is ObservableList) {
            throw DataException("路径 '$path' 不是列表，无法执行列表操作")
        }
        return DataList(this, valuePath)
    }

    override fun toString(): String = "BindableData(keys=${map.keys})"

    // ---------------- 内部 ----------------

    private fun publish(change: DataChange) {
        changesFlow.tryEmit(change)
    }

    private fun read(path: ValuePath): Any? = readFrom(map, path)

    /** 从任意起点沿路径取值；[readFrom] 不检查 config，供元素内部取值复用。 */
    private fun readFrom(start: Any?, path: ValuePath): Any? {
        var current = start
        path.segments.forEach { segment -> current = readSegment(current, segment) }
        return current
    }

    private fun listAt(path: String): ObservableList =
        get(path) as? ObservableList
            ?: throw DataException("路径 '$path' 不是列表，无法执行列表操作")

    /**
     * 列表结构变更：一次操作一条事件，`old` / `new` 是该列表的普通快照。
     *
     * 越界校验在 `op` 里，而 `op` 跑在快照事务内 —— 抛错会中止整个事务，不会留下半截修改，
     * 也不会推事件（`publish` 在 `mutate` 之后）。
     */
    internal fun mutateList(path: String, list: ObservableList, op: (ObservableList) -> Unit) {
        val old = list.toPlain()
        mutate { op(list) }
        publish(DataChange(path, old, list.toPlain(), DataChange.Source.Program))
    }

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

private fun WriteSource.toChangeSource(): DataChange.Source = when (this) {
    WriteSource.Program -> DataChange.Source.Program
    WriteSource.UserBinding -> DataChange.Source.UserBinding
}

/**
 * 在 Compose 里创建一个随调用点存活的 [BindableData]。
 *
 * key 就是参数本身（`remember` 按 `equals` 比较）：同结构的 Map 不会重建，实例保持稳定，
 * 界面因此不会因为一次重组就丢掉用户已经输入的内容；参数**真的变了**才换实例
 * （此时运行时重建、输入重置，这是符合直觉的）。
 *
 * 注意：这里的 Map 会被做一次归一化深拷贝，之后修改传入的 Map **不会**影响界面 ——
 * 要改数据请用返回的对象（`data.set(...)` / `data.list(...)`）。
 *
 * 需要自定义 `Json` 配置时，用 `remember { BindableData.ofJson(json, format) }`，
 * 不要给这个函数加 `format` 参数（内联构造的 `Json { }` 每次重组都是新实例，做 key 会反复重建）。
 */
@Composable
fun rememberBindableData(data: Map<String, Any?>): BindableData = remember(data) { BindableData.of(data) }

/** 同 [rememberBindableData]，入参是 JSON 文本。*/
@Composable
fun rememberBindableData(json: String): BindableData = remember(json) { BindableData.ofJson(json) }
