package cn.changjiahong.composexmlui.store

import cn.changjiahong.composexmlui.binding.BindingExpression
import cn.changjiahong.composexmlui.binding.ValuePath
import cn.changjiahong.composexmlui.core.XmlUiException

/**
 * StoreOwner：管理作用域链。
 *
 * 查找规则：从当前作用域开始逐级向上，第一个 `has(firstSegment)` 成立的作用域负责解析整条路径；
 * 根作用域兜底（data → config）。
 *
 * 写入规则：目标作用域就是「解析时命中的那个作用域」，因此 `{{item.name}}` 的写入会落到
 * Foreach 元素自身（元素是列表中的可观察对象），天然完成数据写回，无需路径重写。
 */
class StoreOwner(
    val store: Store,
    val parent: StoreOwner? = null,
) {

    fun createChild(initialData: Map<String, Any?> = emptyMap()): StoreOwner =
        StoreOwner(ScopeStore(initialData), this)

    fun root(): StoreOwner {
        var current = this
        while (true) {
            current = current.parent ?: return current
        }
    }

    /** 找到负责该路径的作用域。 */
    fun resolveOwner(path: ValuePath): StoreOwner? {
        var current: StoreOwner? = this
        while (current != null) {
            if (current.store.has(path.first)) return current
            current = current.parent
        }
        return null
    }

    fun read(path: ValuePath): Any? = resolveOwner(path)?.store?.get(path)

    fun read(expression: BindingExpression): Any? = when (expression) {
        is BindingExpression.Literal -> expression.raw
        is BindingExpression.Path -> read(expression.path)
    }

    fun write(path: ValuePath, value: Any?) {
        val target = resolveOwner(path) ?: root()
        target.store.set(path, value)
    }

    fun write(expression: BindingExpression, value: Any?) {
        when (expression) {
            is BindingExpression.Literal ->
                throw XmlUiException("字面值属性不可写: '${expression.raw}'")
            is BindingExpression.Path -> write(expression.path, value)
        }
    }

    // ---- 统一读取接口：Renderer 只应通过这些方法读值，避免各自重复解析字符串 ----

    fun readString(expression: BindingExpression): String = ObservableData.asString(read(expression))

    fun readBoolean(expression: BindingExpression): Boolean = ObservableData.asBoolean(read(expression))

    fun readInt(expression: BindingExpression): Int? = when (val value = read(expression)) {
        null -> null
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    fun readFloat(expression: BindingExpression): Float? = when (val value = read(expression)) {
        null -> null
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }

    fun readDouble(expression: BindingExpression): Double? = when (val value = read(expression)) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    override fun toString(): String = "StoreOwner(store=${store::class.simpleName})"
}
