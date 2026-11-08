package cn.changjiahong.composexmlform.api

import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.core.DataException
import cn.changjiahong.composexmlform.core.JsonCodec
import cn.changjiahong.composexmlform.store.ObservableList
import cn.changjiahong.composexmlform.store.readSegment

/**
 * 一个列表的结构句柄：只做增删改，不做读值。
 *
 * 由 `data.list("contacts")` 取得：
 * ```
 * data.list("contacts").add(mapOf("id" to 3, "name" to "新联系人"))
 * data.list("contacts").removeAt(0)
 * ```
 *
 * **每次操作都重新解析路径**，不缓存 [ObservableList]。原因：`reset()` 走
 * `ObservableMap.put` → 会新建列表容器，缓存下来的句柄会静默写到废弃容器上
 * （不报错、界面也不刷新）。重新解析只是几次 map 查找，对点击级操作可忽略。
 *
 * **不提供 `get(index)`**：读取统一走 `data.get("contacts[0].name")`
 * （与双向绑定同一条路径）。句柄只负责结构变更，避免把内部可观察容器泄进对外 API。
 *
 * 越界与「不是列表」抛 [DataException]：取句柄时校验一次，每次操作再校验一次
 * （句柄可能跨 `reset()` 存活，列表长度会变）。
 */
class DataList internal constructor(
    private val owner: BindableData,
    private val path: ValuePath,
) {

    /** 当前长度（实时读，不受句柄创建时间影响）。 */
    val size: Int get() = resolve().size

    /** 末尾追加一项（原地操作，已有元素身份不变，`Foreach` 的 key 与焦点都不丢）。 */
    fun add(value: Any?) {
        val list = resolve()
        val normalized = JsonCodec.normalize(value)
        owner.mutateList(path.raw, list) { it.add(normalized) }
    }

    /** 指定位置插入一项。 */
    fun insert(index: Int, value: Any?) {
        val list = resolve()
        if (index !in 0..list.size) {
            throw DataException("insertListAt: 下标 $index 越界（列表长度 ${list.size}）")
        }
        val normalized = JsonCodec.normalize(value)
        owner.mutateList(path.raw, list) { it.insert(index, normalized) }
    }

    /** 覆盖指定位置的一项。 */
    fun set(index: Int, value: Any?) {
        val list = resolve()
        if (index !in 0 until list.size) {
            throw DataException("setListAt: 下标 $index 越界（列表长度 ${list.size}）")
        }
        val normalized = JsonCodec.normalize(value)
        owner.mutateList(path.raw, list) { it.set(index, normalized) }
    }

    /** 移除指定位置（其余元素身份不变）。 */
    fun removeAt(index: Int) {
        val list = resolve()
        if (index !in 0 until list.size) {
            throw DataException("removeListAt: 下标 $index 越界（列表长度 ${list.size}）")
        }
        owner.mutateList(path.raw, list) { it.removeAt(index) }
    }

    fun clear() {
        val list = resolve()
        owner.mutateList(path.raw, list) { it.clear() }
    }

    /** 整体替换内容（元素身份会重建，慎用于正在输入的列表）。 */
    fun setAll(values: List<Any?>) {
        val list = resolve()
        val normalized = values.map { JsonCodec.normalize(it) }
        owner.mutateList(path.raw, list) {
            it.clear()
            it.addAll(normalized)
        }
    }

    /** 当前内容的普通副本。 */
    fun toList(): List<Any?> = resolve().toPlain()

    private fun resolve(): ObservableList {
        var current: Any? = owner.map
        for (segment in path.segments) {
            current = readSegment(current, segment)
        }
        return current as? ObservableList
            ?: throw DataException("路径 '${path.raw}' 不是列表，无法执行列表操作")
    }
}
