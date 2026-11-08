package cn.changjiahong.composexmlform.node

import cn.changjiahong.composexmlform.binding.BindingParser
import cn.changjiahong.composexmlform.core.XmlUiException

/**
 * 控制节点：If 条件分支与 Foreach 循环。
 *
 * 这两者**不继承样式基类** —— 在 `<If>` / `<Foreach>` 上写 `padding` 会被解析期
 * 直接拒绝，而不是「接受但无效果」。
 *
 * 它们也是布局节点（持有 children），因为分支与循环体的子节点就是它们的内容。
 * `<Then>` / `<Else>` 不是组件，由解析器直接造 [BranchNode]。
 */

open class IfNode(
    val test: ValAttr<Boolean> = valAttr(false),
    children: List<Node> = emptyList(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line) {

    val thenBranch: BranchNode
    val elseBranch: BranchNode?

    init {
        var then: BranchNode? = null
        var otherwise: BranchNode? = null
        children.forEach { child ->
            val branch = child as? BranchNode
                ?: fail(line, "只允许包含 <Then> / <Else>，实际出现 $child")
            when (branch.branchTag) {
                THEN -> {
                    if (then != null) fail(line, "只允许一个 <Then> 分支")
                    then = branch
                }

                ELSE -> {
                    if (otherwise != null) fail(line, "只允许一个 <Else> 分支")
                    otherwise = branch
                }

                else -> fail(line, "未知的分支 <${branch.branchTag}>")
            }
        }
        thenBranch = then ?: fail(line, "缺少必需的分支 <Then>")
        elseBranch = otherwise
    }

    constructor(attrs: Attrs, children: List<Node>) : this(
        test = attrs.valOf("test") ?: valAttr(false),
        children = children,
        id = attrs.id,
        line = attrs.line,
    )

    companion object {
        const val TAG = "If"
        const val THEN = "Then"
        const val ELSE = "Else"

        private fun fail(line: Int, reason: String): Nothing =
            throw XmlUiException(reason, tag = TAG, line = line)
    }
}

/**
 * 列表循环。`item` / `index` / `key` 是**变量名与字段名，不是值**：
 * 绑定它们无法确定按哪一列对齐元素，所以保持普通字符串。
 *
 * `items` 没写时是空列表 —— 循环体不渲染，但不会报错。
 */
open class ForeachNode(
    val items: ValAttr<Any> = valAttr(emptyList<Any>()),
    val itemName: String = "item",
    val indexName: String? = null,
    val keyAttribute: String? = null,
    children: List<Node> = emptyList(),
    id: String? = null,
    line: Int = 0,
) : LayoutNode(children, id, line) {

    constructor(attrs: Attrs, children: List<Node>) : this(
        items = attrs.valOf("items") ?: valAttr(emptyList<Any>()),
        itemName = attrs.raw("item") ?: "item",
        indexName = attrs.raw("index"),
        keyAttribute = attrs.raw("key"),
        children = children,
        id = attrs.id,
        line = attrs.line,
    ) {
        if (children.isEmpty()) attrs.fail("至少需要一个子节点")
        if (!BindingParser.isIdentifier(itemName)) {
            attrs.fail("item 必须是合法变量名，实际为 '$itemName'", "item")
        }
        if (indexName != null && !BindingParser.isIdentifier(indexName)) {
            attrs.fail("index 必须是合法变量名，实际为 '$indexName'", "index")
        }
    }
}
