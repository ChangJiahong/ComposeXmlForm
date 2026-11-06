package cn.changjiahong.composexmlui.node

import androidx.compose.ui.unit.Dp

/**
 * 布局节点：持有 children，具体容器由渲染器创建。
 *
 * 枚举型属性（arrangement / alignment）声明成 `ValAttr<String>`，
 * 字符串 → Compose 枚举的映射留在渲染器：节点定义不依赖排版 API。
 *
 * 两个构造入口：主构造收显式值（含默认值），`(Attrs, children)` 次构造从 XML 读。
 * children 由解析器先建好再传进来 —— 属性包里没有子节点，这是它和 context 的区别。
 */

open class ColumnNode(
    children: List<Node> = emptyList(),
    val scroll: ValAttr<Boolean>? = null,
    val spacing: ValAttr<Dp>? = null,
    val arrangement: ValAttr<String>? = null,
    val horizontalAlignment: ValAttr<String>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line), Styled {

    constructor(attrs: Attrs, children: List<Node>) : this(
        children = children,
        scroll = attrs.valOf("scroll"),
        spacing = attrs.valOf("spacing"),
        arrangement = attrs.valOf("arrangement"),
        horizontalAlignment = attrs.valOf("horizontalAlignment"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}

open class RowNode(
    children: List<Node> = emptyList(),
    val scroll: ValAttr<Boolean>? = null,
    val spacing: ValAttr<Dp>? = null,
    val arrangement: ValAttr<String>? = null,
    val verticalAlignment: ValAttr<String>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line), Styled {

    constructor(attrs: Attrs, children: List<Node>) : this(
        children = children,
        scroll = attrs.valOf("scroll"),
        spacing = attrs.valOf("spacing"),
        arrangement = attrs.valOf("arrangement"),
        verticalAlignment = attrs.valOf("verticalAlignment"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}

open class BoxNode(
    children: List<Node> = emptyList(),
    val contentAlignment: ValAttr<String>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line), Styled {

    constructor(attrs: Attrs, children: List<Node>) : this(
        children = children,
        contentAlignment = attrs.valOf("contentAlignment"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}
