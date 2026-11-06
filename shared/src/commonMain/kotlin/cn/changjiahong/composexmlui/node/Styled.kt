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
 */
interface Styled {
    val width: AttrValue<Dimension>?
    val height: AttrValue<Dimension>?
    val padding: AttrValue<Dp>?
    val paddingHorizontal: AttrValue<Dp>?
    val paddingVertical: AttrValue<Dp>?
    val backgroundColor: AttrValue<Color>?
}

/**
 * [Styled] 的标准实现：一次性读取 6 个样式属性。
 *
 * 用法是委托，节点里只多一段 `Styled by BoxStyle(context)`，不需要逐个声明字段：
 * ```
 * class TextNode(context: NodeContext) : Node(context), Styled by BoxStyle(context)
 * ```
 * 委托在构造期只执行一次，属性名因此只在这一个文件里出现。
 */
class BoxStyle(context: NodeContext) : Styled {
    override val width: AttrValue<Dimension>? = context.attr("width")
    override val height: AttrValue<Dimension>? = context.attr("height")
    override val padding: AttrValue<Dp>? = context.attr("padding")
    override val paddingHorizontal: AttrValue<Dp>? = context.attr("paddingHorizontal")
    override val paddingVertical: AttrValue<Dp>? = context.attr("paddingVertical")
    override val backgroundColor: AttrValue<Color>? = context.attr("backgroundColor")
}
