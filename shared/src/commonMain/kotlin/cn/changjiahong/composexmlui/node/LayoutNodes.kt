package cn.changjiahong.composexmlui.node

import androidx.compose.ui.unit.Dp
import cn.changjiahong.composexmlui.parser.NodeContext

/**
 * 布局节点：持有 children，具体容器由渲染器创建。
 *
 * 枚举型属性（arrangement / alignment）声明成 `AttrValue<String>`，
 * 字符串 → Compose 枚举的映射留在渲染器：节点定义不依赖排版 API。
 *
 * 和叶子节点一样有「显式属性值」与「从 XML 读」两个构造入口。
 */

open class ColumnNode(
    children: List<Node> = emptyList(),
    val scroll: AttrValue<Boolean>? = null,
    val spacing: AttrValue<Dp>? = null,
    val arrangement: AttrValue<String>? = null,
    val horizontalAlignment: AttrValue<String>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line), Styled {

    constructor(context: NodeContext) : this(
        children = context.children,
        scroll = context.attr("scroll"),
        spacing = context.attr("spacing"),
        arrangement = context.attr("arrangement"),
        horizontalAlignment = context.attr("horizontalAlignment"),
        style = BoxStyle(context),
        id = context.id,
        line = context.line,
    )
}

open class RowNode(
    children: List<Node> = emptyList(),
    val scroll: AttrValue<Boolean>? = null,
    val spacing: AttrValue<Dp>? = null,
    val arrangement: AttrValue<String>? = null,
    val verticalAlignment: AttrValue<String>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line), Styled {

    constructor(context: NodeContext) : this(
        children = context.children,
        scroll = context.attr("scroll"),
        spacing = context.attr("spacing"),
        arrangement = context.attr("arrangement"),
        verticalAlignment = context.attr("verticalAlignment"),
        style = BoxStyle(context),
        id = context.id,
        line = context.line,
    )
}

open class BoxNode(
    children: List<Node> = emptyList(),
    val contentAlignment: AttrValue<String>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line), Styled {

    constructor(context: NodeContext) : this(
        children = context.children,
        contentAlignment = context.attr("contentAlignment"),
        style = BoxStyle(context),
        id = context.id,
        line = context.line,
    )
}
