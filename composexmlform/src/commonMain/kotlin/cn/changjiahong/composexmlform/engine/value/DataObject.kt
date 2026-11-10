package cn.changjiahong.composexmlform.engine.value

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import cn.changjiahong.composexmlform.engine.diag.FrozenWriteException

/**
 * 有序键值容器。**唯一实现是 [MutableDataObject]**，`data` / `config` / `action` / `http` 全是它的实例，
 * 差别只有两点：`frozen` 与成员内容（E-7、E-8）。
 *
 * 两条不可违反的契约：
 *
 * 1. **每个 key 一个 `MutableState`** —— 读 `a` 只订阅 `a`，`Foreach` 的元素级更新全靠这个。
 *    退化成「整表一个 state」会让列表任何一格变化都重建全部行。
 * 2. **写入落到原容器**，不得「复制再整体替换」 —— 否则 `u = data.user; u.name = x` 会断链，
 *    通过引用写出去的值不再出现在 `data` 里。
 */
abstract class DataObject {

    abstract val frozen: Boolean

    /** 读成员。不存在时返回 [Absent]，**并且订阅结构变化**（R-1）。 */
    abstract fun member(name: String): Value

    /** 写成员。`frozen` 时抛 [FrozenWriteException]。不发变更事件（事件由 [assign] 发）。 */
    abstract fun setMember(name: String, value: Value)

    /** 删成员。返回是否真的删掉。 */
    abstract fun removeMember(name: String): Boolean

    abstract fun keys(): List<String>

    /** 纯数据快照，可直接序列化。**[Function] 成员被跳过**（代码不是数据）。 */
    abstract fun snapshot(): Map<String, Value>

    /** 变更出口。只有 data 根非空 —— 嵌套容器不知道自己属于哪条路径，无权发事件。 */
    internal open val channel: ChangeChannel? get() = null

    /**
     * 路径感知写入，**全系统唯一发变更事件的地方**。
     *
     * 为什么必须从根调用：路径是「从根走下来的累积段」，容器没有父指针，只有根能拼出完整路径。
     * 中途遇到不存在的节点返回 `false`（不创建中间层 —— 那会让拼写错误静默成功）。
     */
    fun assign(segments: List<String>, value: Value, source: WriteSource): Boolean {
        if (segments.isEmpty()) return false

        var container: Value = this
        val steps = ArrayList<PathStep>(segments.size)

        for (i in 0 until segments.lastIndex) {
            val segment = segments[i]
            steps += PathStep(kindOf(container), segment)
            val next = readIn(container, segment)
            if (next !is DataObject && next !is DataArray) return false
            container = next
        }

        val last = segments.last()
        steps += PathStep(kindOf(container), last)
        val old = readIn(container, last)

        val written = when (container) {
            is DataObject -> {
                container.setMember(last, value)
                true
            }

            is DataArray -> container.set(last.toIntOrNull() ?: return false, value)

            else -> false
        }
        if (!written) return false

        channel?.emit(DataChange(formatPath(steps), old, value, source))
        return true
    }

    /** [assign] 的字符串路径缩写，`"user.list[3].name"`。 */
    fun assign(path: String, value: Value, source: WriteSource): Boolean =
        assign(ParsePath.segmentsOf(path), value, source)

    override fun toString(): String = snapshot().toString()

    companion object {
        /** 建一个可写对象（`data` 根、宿主业务数据）。 */
        fun of(vararg pairs: Pair<String, Any?>): DataObject = dataObjectOf(*pairs)

        fun of(map: Map<String, Any?>): DataObject = dataObjectOf(map)

        /** 建一个只读对象（`config` 根）。整棵子树都会冻结。 */
        fun frozen(map: Map<String, Any?>): DataObject = normalize(map, frozen = true) as DataObject

        val Empty: DataObject get() = MutableDataObject(frozen = true)
    }
}

/**
 * 可变实现。构造时 [frozen] 决定整棵子树能否被写 —— normalize 会把 [frozen] 传给所有后代，
 * 所以 `config` 下的嵌套对象也是冻结的，无法通过「引用泄漏」绕过（V-12 的落地方式）。
 */
class MutableDataObject(
    override val frozen: Boolean = false,
    internal override val channel: ChangeChannel? = null,
) : DataObject() {

    private val values = LinkedHashMap<String, MutableState<Value>>()

    /** 结构版本。只在增删 key 时递增，是「读过不存在字段的读者」的唯一订阅点（R-1）。 */
    private val structure = mutableStateOf(0L)

    override fun member(name: String): Value {
        values[name]?.let { return it.value }
        // 未命中：读一下版本，让「以后新增这个字段」能通知到我。
        // 这里只读不写 —— 组合中写状态会导致无限重组。
        structure.value
        return Absent
    }

    override fun setMember(name: String, value: Value) {
        if (frozen) throw FrozenWriteException(name)
        val existing = values[name]
        if (existing != null) {
            existing.value = normalize(value, frozen)
            return
        }
        values[name] = mutableStateOf(normalize(value, frozen))
        structure.value += 1L
    }

    override fun removeMember(name: String): Boolean {
        if (frozen) throw FrozenWriteException(name)
        if (values.remove(name) == null) return false
        structure.value += 1L
        return true
    }

    override fun keys(): List<String> {
        structure.value // 订阅增删
        return values.keys.toList()
    }

    override fun snapshot(): Map<String, Value> {
        val out = LinkedHashMap<String, Value>(values.size)
        for ((key, state) in values) {
            val raw = state.value
            if (raw is Function) continue // 代码不进快照
            out[key] = snapshotOf(raw)
        }
        return out
    }

    /**
     * 内部：跳过 `frozen` 检查的写入。
     *
     * 只给 `normalize` / 工厂在**构造期**用 —— 冻结对象必须先建好再冻结，否则连初始内容都写不进去。
     * 业务代码一律走 [assign] / [setMember]。
     */
    internal fun setRaw(name: String, value: Value) {
        values[name] = mutableStateOf(value)
        structure.value += 1L
    }
}

/** 一条路径段落在哪种容器上，用于把段拼回可读路径（`list[3]` 而不是 `list.3`）。 */
private class PathStep(val container: ValueKind, val segment: String)

private fun formatPath(steps: List<PathStep>): String = buildString {
    steps.forEachIndexed { index, step ->
        if (step.container == ValueKind.Array) {
            append('[').append(step.segment).append(']')
        } else {
            if (index > 0) append('.')
            append(step.segment)
        }
    }
}

/** 按名字 / 下标读一层。非容器一律 [Absent]。 */
internal fun readIn(container: Value, segment: String): Value = when (container) {
    is DataObject -> container.member(segment)
    is DataArray -> segment.toIntOrNull()?.let { container.get(it) } ?: Absent
    else -> Absent
}

/** 快照：容器递归，标量原样。`Function` 在对象里被跳过、在数组里留 [Absent] 占位（保住下标对齐）。 */
internal fun snapshotOf(value: Value): Value = when (value) {
    is DataObject -> value.snapshot()
    is DataArray -> value.elements.map { if (it is Function) Absent else snapshotOf(it) }
    else -> value
}
