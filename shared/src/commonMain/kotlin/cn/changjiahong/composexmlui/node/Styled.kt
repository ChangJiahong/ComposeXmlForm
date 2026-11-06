package cn.changjiahong.composexmlui.node

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import cn.changjiahong.composexmlui.parser.NodeContext

/**
 * 尺寸语义：`fill` / `wrap` / 具体数值。
 *
 * 把「fill 还是 wrap 还是 12」在解析期就定型，渲染器不用再判断字符串；
 * 同时也消除了「渲染器忘了处理 fill，`"fill".toFloatOrNull() ?: 0.dp` 静默变 0」这类坑。
 *
 * XML 里只写 `12`，不写 `12dp` —— 单位由 [width] / [height] 的类型决定。
 */
sealed interface Dimension {
    data object Fill : Dimension
    data object Wrap : Dimension
    data class Fixed(val dp: Dp) : Dimension
}

/**
 * 可选样式属性组：**只有声明实现它的节点才支持这些属性**。
 *
 * 这一点和「所有节点共有一组属性」的模型不同：`<If>` / `<Foreach>` 没有实现它，
 * 所以在它们上面写 `padding` 会被解析期直接拒绝，而不是「接受但无效果」。
 *
 * 实现方式不是接口委托（`Styled by BoxStyle(context)`），而是暴露一个 [style] 对象、
 * 由接口提供默认 getter：委托只能在主构造的父类型列表里求值，会逼着每个节点把
 * `context` 留在主构造里，而节点的属性值需要能被直接传入。多一层取值没有额外开销。
 */
interface Styled {

    val style: BoxStyle

    val width: AttrValue<Dimension>? get() = style.width
    val height: AttrValue<Dimension>? get() = style.height
    val padding: AttrValue<Dp>? get() = style.padding
    val paddingHorizontal: AttrValue<Dp>? get() = style.paddingHorizontal
    val paddingVertical: AttrValue<Dp>? get() = style.paddingVertical
    val backgroundColor: AttrValue<Color>? get() = style.backgroundColor
}

/**
 * [Styled] 的标准实现：6 个样式属性的容器。
 *
 * 两种构造方式对应两种节点构造方式：
 * - `BoxStyle(context)`：从 XML 读（属性名因此只在这一个文件里出现）；
 * - `BoxStyle(width = ..., padding = ...)`：代码里直接给值。
 */
class BoxStyle(
    override val width: AttrValue<Dimension>? = null,
    override val height: AttrValue<Dimension>? = null,
    override val padding: AttrValue<Dp>? = null,
    override val paddingHorizontal: AttrValue<Dp>? = null,
    override val paddingVertical: AttrValue<Dp>? = null,
    override val backgroundColor: AttrValue<Color>? = null,
) : Styled {

    override val style: BoxStyle get() = this

    constructor(context: NodeContext) : this(
        width = context.attr("width"),
        height = context.attr("height"),
        padding = context.attr("padding"),
        paddingHorizontal = context.attr("paddingHorizontal"),
        paddingVertical = context.attr("paddingVertical"),
        backgroundColor = context.attr("backgroundColor"),
    )
}
