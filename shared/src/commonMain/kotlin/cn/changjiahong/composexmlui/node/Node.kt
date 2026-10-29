package cn.changjiahong.composexmlui.node

import cn.changjiahong.composexmlui.binding.ActionRef
import cn.changjiahong.composexmlui.binding.BindingExpression
import cn.changjiahong.composexmlui.binding.BindingParser

/**
 * 节点树是 XML 与 Compose 渲染之间的中间表示。
 *
 * 职责边界：Node 只保存「界面描述」，不持有 Compose UI、不执行渲染、不订阅业务数据，
 * 因此节点树可以独立解析、校验、缓存与复用。
 */
abstract class Node(
    val attributes: Map<String, String>,
    val line: Int = 0,
) {

    abstract val tag: String

    val id: String? get() = attributes["id"]

    open val children: List<Node> get() = emptyList()

    /** 属性 → 预处理后的表达式对象（字面值也是表达式），解析只发生一次。 */
    val expressions: Map<String, BindingExpression> = attributes.mapValues { (_, value) ->
        BindingParser.parseAttribute(value)
    }

    fun expression(name: String): BindingExpression =
        expressions[name] ?: BindingExpression.Literal("")

    fun expressionOrNull(name: String): BindingExpression? = expressions[name]

    fun raw(name: String): String? = attributes[name]

    override fun toString(): String = if (id != null) "<$tag #$id>" else "<$tag>"
}

/** 布局节点：持有 children，负责创建 Compose 容器并递归渲染。 */
abstract class LayoutNode(
    attributes: Map<String, String>,
    override val children: List<Node>,
    line: Int = 0,
) : Node(attributes, line)

// ---------------------------------------------------------------------------
// 叶子组件节点：不包含 children
// ---------------------------------------------------------------------------

class TextNode(attributes: Map<String, String>, line: Int = 0) : Node(attributes, line) {
    override val tag: String get() = "Text"
}

class ButtonNode(
    attributes: Map<String, String>,
    val action: ActionRef?,
    line: Int = 0,
) : Node(attributes, line) {
    override val tag: String get() = "Button"
}

class TextFieldNode(attributes: Map<String, String>, line: Int = 0) : Node(attributes, line) {
    override val tag: String get() = "TextField"
}

class SelectNode(attributes: Map<String, String>, line: Int = 0) : Node(attributes, line) {
    override val tag: String get() = "Select"
}

// ---------------------------------------------------------------------------
// 布局节点
// ---------------------------------------------------------------------------

class ColumnNode(
    attributes: Map<String, String>,
    override val children: List<Node>,
    line: Int = 0,
) : LayoutNode(attributes, children, line) {
    override val tag: String get() = "Column"
}

class RowNode(
    attributes: Map<String, String>,
    override val children: List<Node>,
    line: Int = 0,
) : LayoutNode(attributes, children, line) {
    override val tag: String get() = "Row"
}

class BoxNode(
    attributes: Map<String, String>,
    override val children: List<Node>,
    line: Int = 0,
) : LayoutNode(attributes, children, line) {
    override val tag: String get() = "Box"
}

// ---------------------------------------------------------------------------
// 控制节点
// ---------------------------------------------------------------------------

/**
 * If 分支容器。`Then` / `Else` 不注册渲染器，由 [IfNode] 的渲染器直接展开其 children，
 * 因此它们只在 If 内部出现。
 */
class BranchNode(
    val branchTag: String,
    override val children: List<Node>,
    line: Int = 0,
) : LayoutNode(emptyMap(), children, line) {
    override val tag: String get() = branchTag
}

class IfNode(
    attributes: Map<String, String>,
    val test: BindingExpression,
    val thenBranch: BranchNode,
    val elseBranch: BranchNode?,
    line: Int = 0,
) : Node(attributes, line) {
    override val tag: String get() = "If"

    /** 供遍历 / 校验使用。 */
    override val children: List<Node> get() = listOfNotNull(thenBranch, elseBranch)
}

class ForeachNode(
    attributes: Map<String, String>,
    val items: BindingExpression,
    val itemName: String,
    val indexName: String?,
    val keyAttribute: String?,
    override val children: List<Node>,
    line: Int = 0,
) : LayoutNode(attributes, children, line) {
    override val tag: String get() = "Foreach"
}
