package cn.changjiahong.composexmlform.node

/**
 * 布局节点：持有 children，具体容器由渲染器创建。
 *
 * 枚举型属性（arrangement / alignment）声明成 `ValAttr<String>`，
 * 字符串 → Compose 枚举的映射留在渲染器：节点定义不依赖排版 API。
 *
 * 三个容器都继承 [StyledLayoutNode]：`children` 与 `style` 由基类提供，
 * 子类只声明自己的业务属性 —— 与叶子节点一样是**一个 `(Attrs, children)` 构造**。
 * children 由解析器先建好再传进来（属性包里没有子节点，这是它和 context 的区别）。
 */

open class ColumnNode(
    attrs: Attrs = Attrs.Empty,
    children: List<Node> = emptyList(),
) : StyledLayoutNode(attrs, children) {

    val scroll: ValAttr<Boolean>? = attrs.valOf("scroll")

    /** 子项间距数值（单位 dp 由渲染器加）。 */
    val spacing: ValAttr<Float>? = attrs.valOf("spacing")
    val arrangement: ValAttr<String>? = attrs.valOf("arrangement")
    val horizontalAlignment: ValAttr<String>? = attrs.valOf("horizontalAlignment")
}

open class RowNode(
    attrs: Attrs = Attrs.Empty,
    children: List<Node> = emptyList(),
) : StyledLayoutNode(attrs, children) {

    val scroll: ValAttr<Boolean>? = attrs.valOf("scroll")
    val spacing: ValAttr<Float>? = attrs.valOf("spacing")
    val arrangement: ValAttr<String>? = attrs.valOf("arrangement")
    val verticalAlignment: ValAttr<String>? = attrs.valOf("verticalAlignment")
}

open class BoxNode(
    attrs: Attrs = Attrs.Empty,
    children: List<Node> = emptyList(),
) : StyledLayoutNode(attrs, children) {

    val contentAlignment: ValAttr<String>? = attrs.valOf("contentAlignment")
}
