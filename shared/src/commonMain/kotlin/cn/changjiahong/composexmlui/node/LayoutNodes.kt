package cn.changjiahong.composexmlui.node

import androidx.compose.ui.unit.Dp
import cn.changjiahong.composexmlui.parser.NodeContext

/**
 * 布局节点：持有 children，具体容器由渲染器创建。
 *
 * 枚举型属性（arrangement / alignment）声明成 `AttrValue<String>`，
 * 字符串 → Compose 枚举的映射留在渲染器：节点定义不依赖排版 API。
 */
open class ColumnNode(context: NodeContext) : LayoutNode(context, context.children),
    Styled by BoxStyle(context) {

    val scroll: AttrValue<Boolean>? = context.attr("scroll")
    val spacing: AttrValue<Dp>? = context.attr("spacing")
    val arrangement: AttrValue<String>? = context.attr("arrangement")
    val horizontalAlignment: AttrValue<String>? = context.attr("horizontalAlignment")
}

open class RowNode(context: NodeContext) : LayoutNode(context, context.children),
    Styled by BoxStyle(context) {

    val scroll: AttrValue<Boolean>? = context.attr("scroll")
    val spacing: AttrValue<Dp>? = context.attr("spacing")
    val arrangement: AttrValue<String>? = context.attr("arrangement")
    val verticalAlignment: AttrValue<String>? = context.attr("verticalAlignment")
}

open class BoxNode(context: NodeContext) : LayoutNode(context, context.children),
    Styled by BoxStyle(context) {

    val contentAlignment: AttrValue<String>? = context.attr("contentAlignment")
}
