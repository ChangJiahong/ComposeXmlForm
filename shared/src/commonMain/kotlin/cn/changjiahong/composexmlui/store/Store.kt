package cn.changjiahong.composexmlui.store

import cn.changjiahong.composexmlui.binding.PathSegment
import cn.changjiahong.composexmlui.binding.ValuePath
import cn.changjiahong.composexmlui.core.XmlUiException

/**
 * Store 只负责「当前作用域的数据」：读取、写入、可观察状态、快照导出。
 * 作用域父子关系由 [StoreOwner] 负责。
 */
interface Store {

    /** 当前作用域是否声明了某个变量 / 字段（用于作用域向上查找）。 */
    fun has(firstSegment: String): Boolean

    fun get(path: ValuePath): Any?

    fun set(path: ValuePath, value: Any?)

    /** 当前作用域业务数据的普通副本（不包含 config、不包含父作用域）。 */
    fun getData(): Map<String, Any?>
}

/** 全局配置在路径中的变量名。 */
const val CONFIG_KEY = "config"

/**
 * 根 Store：同时持有业务数据 data（可写）与只读配置 config。
 *
 * 读取顺序：`config.xxx` → 全局配置；其余 → 业务数据。
 */
class RootStore(
    data: Map<String, Any?>,
    config: Map<String, Any?>,
) : Store {

    private val dataMap: ObservableMap = ObservableMap(data)
    private val configMap: ObservableMap = ObservableMap(config)

    /** 根作用域兜底：任何变量名都由根处理（data 优先，其次 config）。 */
    override fun has(firstSegment: String): Boolean = true

    override fun get(path: ValuePath): Any? {
        val fromConfig = path.first == CONFIG_KEY
        var current: Any? = if (fromConfig) configMap else dataMap
        val start = if (fromConfig) 1 else 0
        for (index in start until path.segments.size) {
            current = readSegment(current, path.segments[index])
        }
        return current
    }

    override fun set(path: ValuePath, value: Any?) {
        if (path.first == CONFIG_KEY) {
            throw XmlUiException("config 是只读配置，不支持写回: ${path.raw}")
        }
        writeInto(dataMap, path, value)
    }

    override fun getData(): Map<String, Any?> = dataMap.toPlain()

    /** 直接暴露可观察的业务数据根，供控制节点 / 扩展组件使用。 */
    fun dataRoot(): ObservableMap = dataMap

    fun configRoot(): ObservableMap = configMap
}

/**
 * 作用域 Store：只保存局部变量（Foreach 的 item/index、自定义组件的局部变量）。
 */
class ScopeStore(initialVars: Map<String, Any?>) : Store {

    private val varsMap: ObservableMap = ObservableMap(initialVars)

    override fun has(firstSegment: String): Boolean = varsMap.contains(firstSegment)

    override fun get(path: ValuePath): Any? {
        var current: Any? = varsMap
        for (segment in path.segments) {
            current = readSegment(current, segment)
        }
        return current
    }

    override fun set(path: ValuePath, value: Any?) {
        writeInto(varsMap, path, value)
    }

    override fun getData(): Map<String, Any?> = varsMap.toPlain()

    fun putVar(name: String, value: Any?) = varsMap.put(name, value)

    fun vars(): ObservableMap = varsMap
}

internal fun readSegment(container: Any?, segment: PathSegment): Any? = when (segment) {
    is PathSegment.Prop -> (container as? ObservableMap)?.get(segment.name)
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
                ?: throw XmlUiException("写入 ${path.raw} 失败：'${describe(last)}' 的父级不是对象")
            map.put(last.name, value)
        }
        is PathSegment.Index -> {
            val list = container as? ObservableList
                ?: throw XmlUiException("写入 ${path.raw} 失败：'${describe(last)}' 的父级不是列表")
            list.set(last.index, value)
        }
    }
}

private fun describe(segment: PathSegment): String = when (segment) {
    is PathSegment.Prop -> segment.name
    is PathSegment.Index -> "[${segment.index}]"
}
