package cn.changjiahong.composexmlform.node

/**
 * 尺寸语义：`fill` / `wrap` / 具体数值。
 *
 * 把「fill 还是 wrap 还是 12」在解析期就定型，渲染器不用再判断字符串；
 * 同时也消除了「渲染器忘了处理 fill，`"fill".toFloatOrNull() ?: 0.dp` 静默变 0」这类坑。
 *
 * XML 里只写 `12`，不写 `12dp` —— 单位由 [width] / [height] 的类型决定；
 * [Fixed] 存的是**数值**（`Float`），`Dp` 由渲染器加（见包边界约定）。
 */
sealed interface Dimension {
    data object Fill : Dimension
    data object Wrap : Dimension
    data class Fixed(val value: Float) : Dimension
}

/**
 * 带样式的**叶子**节点基类：6 个样式属性下沉到这里，`Text` / `Button` / `TextField` /
 * `Select` 以及自定义组件继承它即可，不用再各写一遍 `override val style`。
 *
 * 为什么是基类而不是接口（原来是 `interface Styled`）：`default val padding get() = style.padding`
 * 这 6 个转发 getter 每个节点都要继承一次，接口无法承载状态，节点还是得自己声明
 * `override val style = BoxStyle()` —— 接口只起到「标记」作用，转发代码一行没省。
 * 下沉成基类后，「有样式」这件事由**父类**表达，属性只声明一次。
 *
 * 容器节点不能继承它（`children` 是 [LayoutNode] 的字段），用 [StyledLayoutNode]。
 * `<If>` / `<Foreach>` 两者都不继承，所以它们上面写 `padding` 会被解析期拒绝 ——
 * 拦它的是 `(attrs)` 构造压根不读样式属性，不是类型。
 */
abstract class StyledNode(
    val style: BoxStyle,
    id: String? = null,
    line: Int = 0,
) : Node(id, line) {

    constructor(attrs: Attrs) : this(BoxStyle(attrs), attrs.id, attrs.line)
}

/**
 * 带样式的**容器**节点基类：`children` 与 `style` 都在。
 *
 * 与 [StyledNode] 分成两支，是 Kotlin 单继承的必然结果：[LayoutNode] 持有 children、
 * [StyledNode] 持有 style，而「既是容器又有样式」的节点（`Row` / `Column` / `Box`）
 * 两个都要 —— 只能合成第三个基类，而不是把 `children` 塞进 StyledNode
 * （那样叶子节点也会「是容器」，`node is LayoutNode` 就失去意义）。
 *
 * 两个 `(attrs)` 构造直接就是「从 XML 建节点」的入口，子类只声明自己的业务属性。
 */
abstract class StyledLayoutNode(
    val style: BoxStyle,
    children: List<Node>,
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line) {

    constructor(attrs: Attrs, children: List<Node>) :
        this(BoxStyle(attrs), children, attrs.id, attrs.line)
}

/**
 * 拿到节点的样式包，没有样式的节点（`<If>` / `<Foreach>` / `<Then>`）返回 `null`。
 *
 * 给「不关心节点是叶子还是容器」的地方用（调试 dump、工具函数、宿主检查），
 * 渲染器不要用它 —— 渲染器手上就是具体节点类型，直接用 `node.style` 更直接。
 */
val Node.boxStyle: BoxStyle?
    get() = when (this) {
        is StyledNode -> style
        is StyledLayoutNode -> style
        else -> null
    }

/**
 * 6 个样式属性的容器。
 *
 * 两种构造方式对应两种节点构造方式：
 * - `BoxStyle(attrs)`：从 XML 读（属性名因此只在这一个文件里出现）；
 * - `BoxStyle(width = ..., padding = ...)`：代码里直接给值。
 *
 * 它不再实现任何接口：谁持有一个 `BoxStyle`，谁就能 `style.containerModifier()`。
 */
class BoxStyle(
    val width: AttrValue<Dimension>? = null,
    val height: AttrValue<Dimension>? = null,
    val padding: AttrValue<Float>? = null,
    val paddingHorizontal: AttrValue<Float>? = null,
    val paddingVertical: AttrValue<Float>? = null,

    /** 颜色写成字符串（`#FF5722` / `red`），渲染期由 `toColor()` 换算。 */
    val backgroundColor: AttrValue<String>? = null,
) {

    constructor(attrs: Attrs) : this(
        width = attrs.valOf("width"),
        height = attrs.valOf("height"),
        padding = attrs.valOf("padding"),
        paddingHorizontal = attrs.valOf("paddingHorizontal"),
        paddingVertical = attrs.valOf("paddingVertical"),
        backgroundColor = attrs.valOf("backgroundColor"),
    )
}
