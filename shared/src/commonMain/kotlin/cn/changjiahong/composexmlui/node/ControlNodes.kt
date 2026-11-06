package cn.changjiahong.composexmlui.node

import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.parser.NodeContext

/**
 * 控制节点：If 条件分支与 Foreach 循环。
 *
 * 这两者**不实现 [Styled]** —— 在 `<If>` / `<Foreach>` 上写 `padding` 会被解析期
 * 直接拒绝，而不是「接受但无效果」。
 *
 * 它们也有显式构造入口，但只搬运结构（分支 / 循环变量名），
 * XML 形状校验（必须有子节点、变量名合法）留在从 XML 构造的那条路上。
 */

open class IfNode(
    val test: AttrValue<Boolean>,
    val thenBranch: BranchNode,
    val elseBranch: BranchNode? = null,
    id: String? = null,
    line: Int = 0,
) : Node(id, line) {

    constructor(context: NodeContext) : this(
        test = context.need("test"),
        thenBranch = context.requireBranch("Then"),
        elseBranch = context.optionalBranch("Else"),
        id = context.id,
        line = context.line,
    ) {
        context.ensureChildTags("Then", "Else")
    }

    /** 供遍历 / 校验使用。 */
    override val children: List<Node> get() = listOfNotNull(thenBranch, elseBranch)
}

/**
 * 列表循环。`item` / `index` / `key` 是**变量名与字段名，不是值**：
 * 绑定它们无法确定按哪一列对齐元素，所以保持普通字符串。
 */
open class ForeachNode(
    val items: VarAttr<Any>,
    val itemName: String = "item",
    val indexName: String? = null,
    val keyAttribute: String? = null,
    children: List<Node> = emptyList(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line) {

    constructor(context: NodeContext) : this(
        items = context.needWritable("items"),
        itemName = context.optional("item") ?: "item",
        indexName = context.optional("index"),
        keyAttribute = context.optional("key"),
        children = context.children,
        id = context.id,
        line = context.line,
    ) {
        context.ensureChildren()
        if (!BindingParser.isIdentifier(itemName)) {
            context.fail("item 必须是合法变量名，实际为 '$itemName'", "item")
        }
        if (indexName != null && !BindingParser.isIdentifier(indexName)) {
            context.fail("index 必须是合法变量名，实际为 '$indexName'", "index")
        }
    }
}
