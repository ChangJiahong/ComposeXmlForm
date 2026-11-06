package cn.changjiahong.composexmlui.node

import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.parser.NodeContext

/**
 * 控制节点：If 条件分支与 Foreach 循环。
 *
 * 这两者**不实现 [Styled]** —— 在 `<If>` / `<Foreach>` 上写 `padding` 会被解析期
 * 直接拒绝，而不是「接受但无效果」。
 */

open class IfNode(context: NodeContext) : Node(context) {

    val test: AttrValue<Boolean> = context.need("test")
    val thenBranch: BranchNode = context.requireBranch("Then")
    val elseBranch: BranchNode? = context.optionalBranch("Else")

    init {
        context.ensureChildTags("Then", "Else")
    }

    /** 供遍历 / 校验使用。 */
    override val children: List<Node> get() = listOfNotNull(thenBranch, elseBranch)
}

/**
 * 列表循环。`item` / `index` / `key` 是**变量名与字段名，不是值**：
 * 绑定它们无法确定按哪一列对齐元素，所以保持普通字符串。
 */
open class ForeachNode(context: NodeContext) : LayoutNode(context, context.children) {

    val items: AttrValue<Any> = context.needWritable("items")
    val itemName: String = context.optional("item") ?: "item"
    val indexName: String? = context.optional("index")
    val keyAttribute: String? = context.optional("key")

    init {
        context.ensureChildren()
        if (!BindingParser.isIdentifier(itemName)) {
            context.fail("item 必须是合法变量名，实际为 '$itemName'", "item")
        }
        if (indexName != null && !BindingParser.isIdentifier(indexName)) {
            context.fail("index 必须是合法变量名，实际为 '$indexName'", "index")
        }
    }
}
