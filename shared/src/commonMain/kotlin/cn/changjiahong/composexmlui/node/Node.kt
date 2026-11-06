package cn.changjiahong.composexmlui.node

import cn.changjiahong.composexmlui.parser.NodeContext
import kotlin.random.Random

/**
 * 节点树是 XML 与渲染之间的中间表示。
 *
 * 职责边界：
 * - 只保存「界面描述」，属性是**显式字段**而不是 `Map<String, String>`：
 *   拼错属性名是编译错误，必填属性漏传也是编译错误。
 * - 不持有渲染逻辑，因此节点树可以独立解析、校验、缓存与复用；
 *   渲染由 [cn.changjiahong.composexmlui.renderer.NodeRenderer] 按类型分派。
 * - 属性值可以是字面量或绑定表达式（[AttrValue]），但节点自身始终不可变。
 *
 * 基类只保留两个字段：[id]（节点身份）与 [line]（解析期错误定位）。
 * 样式类属性不进基类 —— 需要它们的节点自己实现 [Styled]。
 */
abstract class Node protected constructor(
    id: String?,
    val line: Int,
) {

    /**
     * 节点身份：XML 写了 `id` 就用它，缺省自动生成。
     *
     * 自动 id 在同一棵被缓存的节点树里稳定（`LayoutCache` 复用整棵树），
     * 只有 XML 变化、重新解析时才会变。
     */
    val id: String = id ?: nextAutoId()

    /** 从解析上下文构建：id 与行号都来自 XML。 */
    protected constructor(context: NodeContext) : this(context.optional("id"), context.line)

    /** 没有 XML 来源的节点（`<Then>` / `<Else>`）用。 */
    protected constructor(line: Int) : this(null, line)

    open val children: List<Node> get() = emptyList()

    override fun toString(): String = "<${this::class.simpleName} #$id>"
}

/** 布局节点：持有 children。真正的容器创建在渲染器里完成。 */
abstract class LayoutNode protected constructor(
    id: String?,
    children: List<Node>,
    line: Int,
) : Node(id, line) {

    override val children: List<Node> = children

    protected constructor(context: NodeContext, children: List<Node>) :
        this(context.optional("id"), children, context.line)
}

/**
 * If 的分支容器。`Then` / `Else` 不注册标签，由 `IfNode` 的解析逻辑直接构造，
 * 因此它们没有属性，id 自动生成。
 */
class BranchNode(
    val branchTag: String,
    children: List<Node>,
    line: Int = 0,
) : LayoutNode(null, children, line)

/**
 * 自动 id。用随机串而不是自增计数器，是为了让两棵并行解析的节点树（两个界面实例）
 * 不会撞号；同时带 `auto_` 前缀，便于和用户显式声明的 id 区分。
 */
private fun nextAutoId(): String = "auto_" + Random.nextLong().toULong().toString(16)
