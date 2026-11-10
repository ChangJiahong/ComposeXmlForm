package cn.changjiahong.composexmlform.engine.value

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import cn.changjiahong.composexmlform.engine.diag.FrozenWriteException

/**
 * 有序列表。与 [DataObject] 同构：**每个元素一个 `MutableState`**，外加一个结构版本。
 *
 * 越界读返回 [Absent]（V-5），不抛 —— 列表长度随数据变化，`{{list[5].name}}` 在只有 3 条时
 * 应该渲染成空而不是让整页白屏；结构版本保证第 6 条被 append 进来时这个节点会重组。
 */
class DataArray private constructor(
    private val items: MutableList<MutableState<Value>>,
    val frozen: Boolean,
) {

    constructor(frozen: Boolean = false) : this(mutableListOf(), frozen)

    /** 结构版本。只在增删元素时递增。 */
    private val structure = mutableStateOf(0L)

    val size: Int
        get() {
            structure.value
            return items.size
        }

    /** 逐元素读值。遍历它会订阅全部元素（`Foreach` 就该如此）。 */
    val elements: List<Value>
        get() = items.map { it.value }

    fun get(index: Int): Value {
        if (index !in items.indices) {
            structure.value // 越界：订阅「以后变长」
            return Absent
        }
        return items[index].value
    }

    fun set(index: Int, value: Value): Boolean {
        if (frozen) throw FrozenWriteException("[$index]")
        if (index !in items.indices) return false
        items[index].value = normalize(value, frozen)
        return true
    }

    fun append(value: Value): DataArray {
        if (frozen) throw FrozenWriteException("[$size]")
        items += mutableStateOf(normalize(value, frozen))
        structure.value += 1L
        return this
    }

    fun insert(index: Int, value: Value): Boolean {
        if (frozen) throw FrozenWriteException("[$index]")
        if (index !in 0..items.size) return false
        items.add(index, mutableStateOf(normalize(value, frozen)))
        structure.value += 1L
        return true
    }

    fun removeAt(index: Int): Value {
        if (frozen) throw FrozenWriteException("[$index]")
        if (index !in items.indices) return Absent
        val removed = items.removeAt(index).value
        structure.value += 1L
        return removed
    }

    fun indexOf(value: Value): Int = items.indexOfFirst { valuesEqual(it.value, value) }

    /** 内部：跳过 `frozen` 检查，只给 `normalize` / 工厂在构造期用。 */
    internal fun appendRaw(value: Value) {
        items += mutableStateOf(value)
        structure.value += 1L
    }

    override fun toString(): String = elements.toString()

    companion object {
        /**
         * 从原始集合构造，逐项规范化。
         *
         * 用 [appendRaw] 而不是 [append]：构造期还没有「外部写入」这回事，
         * `frozen` 只该拦**之后**的写。用 `append` 会让 `DataObject.frozen(mapOf("list" to listOf(…)))`
         * 在造 config 时就抛 `FrozenWriteException` —— 而它正是最需要能建的那一种。
         */
        fun ofValues(values: Iterable<Value>, frozen: Boolean = false): DataArray {
            val array = DataArray(frozen)
            for (v in values) array.appendRaw(normalize(v, frozen))
            return array
        }
    }
}
