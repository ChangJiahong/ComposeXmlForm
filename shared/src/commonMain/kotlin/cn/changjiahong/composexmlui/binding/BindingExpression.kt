package cn.changjiahong.composexmlui.binding

/**
 * 预处理后的绑定表达式：XML 属性字符串只解析一次，运行时直接复用对象。
 *
 * - [Literal]：普通字面值，例如 `text="提交"`。
 * - [Path]：数据绑定，例如 `value="{{username}}"`、`items="{{config.regions}}"`。
 */
sealed class BindingExpression {

    /** 原始属性文本。 */
    abstract val raw: String

    /** 是否为字面值（非绑定）。 */
    open val isLiteral: Boolean get() = false

    /** 绑定路径文本（不含花括号），字面值为 null。 */
    open val pathText: String? get() = null

    class Literal(override val raw: String) : BindingExpression() {
        override val isLiteral: Boolean get() = true
        override fun toString(): String = "Literal($raw)"
    }

    class Path(val path: ValuePath) : BindingExpression() {
        override val raw: String get() = "{{${path.raw}}}"
        override val pathText: String get() = path.raw
        override fun toString(): String = "Binding(${path.raw})"
    }
}

/**
 * 一条数据访问路径，例如 `user.name`、`config.regions`、`contacts[0].name`。
 *
 * 第一段一定是 [PathSegment.Prop]，因此 [first] 可以直接取到变量名。
 */
class ValuePath(val raw: String, val segments: List<PathSegment>) {

    val first: String
        get() = (segments.first() as PathSegment.Prop).name

    /** 是否为单段路径（只有一个变量名）。 */
    val isSimple: Boolean get() = segments.size == 1

    companion object {
        fun parse(text: String): ValuePath = BindingParser.parsePath(text, text)
    }

    override fun toString(): String = raw
}

sealed interface PathSegment {
    data class Prop(val name: String) : PathSegment
    data class Index(val index: Int) : PathSegment
}

/**
 * 动作引用：只允许无参函数调用 `action="submit()"`。
 */
class ActionRef(val name: String, val raw: String) {
    override fun toString(): String = "$name()"
}
