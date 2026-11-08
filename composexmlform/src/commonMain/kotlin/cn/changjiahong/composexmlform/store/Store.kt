package cn.changjiahong.composexmlform.store

import cn.changjiahong.composexmlform.binding.PathSegment
import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.core.XmlUiException

/**
 * 一次写入的来源。
 *
 * 存在的唯一原因：宿主需要区分「程序改的」与「用户改的」——
 * 脏检查、离开拦截、增量提交都依赖这个区别，而数据层本身看不出来。
 * 第三方直接调 [ObservableMap.put] 时没有来源，一律按 [Program] 处理（唯一不受保证的入口）。
 */
enum class WriteSource {
    /** 业务代码写入：[Store] 的默认来源。 */
    Program,

    /** 双向绑定写回（TextField / Select / 自定义组件的 `VarAttr`）。 */
    UserBinding,
}

/**
 * Store 只负责「当前作用域的数据」：读取、写入、可观察状态、快照导出。
 * 作用域父子关系由 [StoreOwner] 负责。
 */
interface Store {

    /** 当前作用域是否声明了某个变量 / 字段（用于作用域向上查找）。 */
    fun has(firstSegment: String): Boolean

    fun get(path: ValuePath): Any?

    fun set(path: ValuePath, value: Any?, source: WriteSource = WriteSource.Program)

    /** 当前作用域业务数据的普通副本（不包含 config、不包含父作用域）。 */
    fun getData(): Map<String, Any?>
}

/**
 * 根 Store：持有业务数据根。
 *
 * 业务数据根由调用方的 `BindableData` 持有并传入（同一个 [ObservableMap] 实例），
 * 因此业务层写入、`asState` / `observe` 订阅、引擎的双向绑定写回共享同一份状态。
 *
 * 这里**没有 config**：`config` 是解析期资源，`{{config.x}}` 在解析时就变成了常量。
 * 运行时只剩一份可写数据，作用域查找因此没有特例分支。
 *
 * [onChanged] 是「一次写入操作」的回调，由 `BindableData` 注入，用于把变更汇总成
 * 一条事件推给 `changes()`。它拿到的是写入前后的普通值（已 unwrap），可以直接交给业务层。
 */
class RootStore(
    dataRoot: ObservableMap,
    private val onChanged: ((ValuePath, Any?, Any?, WriteSource) -> Unit)? = null,
) : Store {

    private val dataMap: ObservableMap = dataRoot

    /** 根作用域兜底：任何变量名都由根处理。 */
    override fun has(firstSegment: String): Boolean = true

    override fun get(path: ValuePath): Any? {
        var current: Any? = dataMap
        for (segment in path.segments) {
            current = readSegment(current, segment)
        }
        return current
    }

    override fun set(path: ValuePath, value: Any?, source: WriteSource) {
        val old = if (onChanged == null) null else ObservableData.unwrap(get(path))
        writeInto(dataMap, path, value)
        onChanged?.invoke(path, old, ObservableData.unwrap(get(path)), source)
    }

    override fun getData(): Map<String, Any?> = dataMap.toPlain()

    /** 直接暴露可观察的业务数据根，供控制节点 / 扩展组件使用。 */
    fun dataRoot(): ObservableMap = dataMap
}

/**
 * 作用域 Store：只保存局部变量（Foreach 的 item/index、自定义组件的局部变量）。
 *
 * 变量**原样存放**（[ObservableMap.putRaw]），不做可观察包装：`Foreach` 的元素来自数据源时
 * 本来就是 `ObservableMap`，写回照旧；来自解析期常量（`{{config.xxx}}`）时是普通 `Map`，
 * 保持原样才能让「写回只读常量」当场报错，而不是写进一个没人读的副本。
 */
class ScopeStore(initialVars: Map<String, Any?>) : Store {

    private val varsMap: ObservableMap = ObservableMap(emptyMap()).also { map ->
        initialVars.forEach { (name, value) -> map.putRaw(name, value) }
    }

    override fun has(firstSegment: String): Boolean = varsMap.contains(firstSegment)

    override fun get(path: ValuePath): Any? {
        var current: Any? = varsMap
        for (segment in path.segments) {
            current = readSegment(current, segment)
        }
        return current
    }

    override fun set(path: ValuePath, value: Any?, source: WriteSource) {
        writeInto(varsMap, path, value)
    }

    override fun getData(): Map<String, Any?> = varsMap.toPlain()

    fun putVar(name: String, value: Any?) = varsMap.putRaw(name, value)

    fun vars(): ObservableMap = varsMap
}

/**
 * 沿路径读一个片段。
 *
 * 同时接受可观察容器与**普通** `Map` / `List`：解析期常量（如 `config` 展开后的列表）
 * 是不带 `MutableState` 的普通结构，但它同样要能被 `Foreach` / `Select` 读到。
 */
internal fun readSegment(container: Any?, segment: PathSegment): Any? = when (segment) {
    is PathSegment.Prop -> when (container) {
        is ObservableMap -> container.get(segment.name)
        is Map<*, *> -> container[segment.name]
        else -> null
    }

    is PathSegment.Index -> when (container) {
        is ObservableList -> if (segment.index in 0 until container.size) container.get(segment.index) else null
        is List<*> -> if (segment.index in container.indices) container[segment.index] else null
        else -> null
    }
}

internal fun writeInto(root: ObservableMap, path: ValuePath, value: Any?) {
    var container: Any? = root
    for (index in 0 until path.segments.size - 1) {
        val segment = path.segments[index]
        val next = readSegment(container, segment)
        if (next == null) {
            throw XmlUiException(
                "写入 ${path.raw} 失败：路径中的 '${describe(segment)}' 不存在或为 null",
            )
        }
        container = next
    }
    when (val last = path.segments.last()) {
        is PathSegment.Prop -> {
            val map = container as? ObservableMap
                ?: throw XmlUiException(
                    "写入 ${path.raw} 失败：'${describe(last)}' 的父级是只读常量（来自 config），不能写回",
                )
            map.put(last.name, value)
        }

        is PathSegment.Index -> {
            val list = container as? ObservableList
                ?: throw XmlUiException(
                    "写入 ${path.raw} 失败：'${describe(last)}' 的父级是只读常量（来自 config），不能写回",
                )
            list.set(last.index, value)
        }
    }
}

private fun describe(segment: PathSegment): String = when (segment) {
    is PathSegment.Prop -> segment.name
    is PathSegment.Index -> "[${segment.index}]"
}
