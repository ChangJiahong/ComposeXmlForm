package cn.changjiahong.composexmlform

import cn.changjiahong.composexmlform.node.AttrValue
import cn.changjiahong.composexmlform.node.BoxNode
import cn.changjiahong.composexmlform.node.BranchNode
import cn.changjiahong.composexmlform.node.ButtonNode
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.ForeachNode
import cn.changjiahong.composexmlform.node.IfNode
import cn.changjiahong.composexmlform.node.LayoutNode
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.RowNode
import cn.changjiahong.composexmlform.node.SelectNode
import cn.changjiahong.composexmlform.node.StyledLayoutNode
import cn.changjiahong.composexmlform.node.StyledNode
import cn.changjiahong.composexmlform.node.boxStyle
import cn.changjiahong.composexmlform.node.TextFieldNode
import cn.changjiahong.composexmlform.node.TextNode
import cn.changjiahong.composexmlform.node.ValAttr

/**
 * 节点树的归一化文本表示，专供快照测试。
 *
 * 两个刻意的取舍：
 * - **不打印自动 id**（`auto_*`）。`Node.nextAutoId()` 用 `Random`，打出来快照永远不过。
 *   显式声明的 id 用 `#id` 打印，所以 `id` 属性仍被覆盖。
 * - **只打印属性名与 String 常量值**，不打印 `Dp` / `TextUnit` / `Color` 这类值的
 *   具体形态。原因：R6 的批次 0 会把这些类型换成 `Float` / `String`，
 *   打印值会让快照因为「类型变了」而大面积失配，掩盖真正要抓的问题
 *   ——「属性读漏 / 读到别的名字上」。具体值仍由 `AttrValueTest` / `RegistrationTest` 断言。
 *
 * 绑定槽位打印成 `name=*`（`VarAttr`）或 `name`（`ValAttr` 且无常量）。
 *
 * 这是测试专用代码：按节点类型分支的写法在引擎里是缺点（没有反射，KMP 只能这样），
 * 在测试里反而是优点 —— 新增节点类型时这里不写就看不见。
 */
internal fun dumpTree(node: Node, depth: Int = 0): String = buildString {
    append("  ".repeat(depth))
    append(header(node))
    append('\n')
    if (node is LayoutNode) node.children.forEach { append(dumpTree(it, depth + 1)) }
}

private fun header(node: Node): String {
    // 类名去掉 Node 后缀：`TextNode` → `Text`，与 XML 标签对齐，读起来一眼能对上是哪一行 XML。
    val name = (node::class.simpleName ?: "?").removeSuffix("Node")
    val id = if (node.id.startsWith("auto_")) "" else "#${node.id}"
    val attrs = attributesOf(node)
    val suffix = if (attrs.isEmpty()) "" else attrs.joinToString(", ", prefix = " [", postfix = "]")
    return "$name$id$suffix"
}

private fun attributesOf(node: Node): List<String> {
    val out = mutableListOf<String>()

    fun put(name: String, value: AttrValue<*>?) {
        val text = render(name, value) ?: return
        out += text
    }

    fun putRaw(name: String, value: String?) {
        if (value != null) out += "$name=\"$value\""
    }

    when (node) {
        is TextNode -> {
            put("value", node.value); put("fontSize", node.fontSize); put("color", node.color)
            put("maxLines", node.maxLines); put("fontWeight", node.fontWeight); put("textAlign", node.textAlign)
        }

        is ButtonNode -> {
            put("text", node.text)
            node.action?.constant?.let { out += "action=${it.raw}" }
            put("enabled", node.enabled)
        }

        is TextFieldNode -> {
            put("value", node.value); put("label", node.label); put("placeholder", node.placeholder)
            put("readOnly", node.readOnly); put("enabled", node.enabled); put("singleLine", node.singleLine)
        }

        is SelectNode -> {
            put("options", node.options); put("value", node.value); put("label", node.label)
            put("placeholder", node.placeholder); put("enabled", node.enabled)
        }

        is ColumnNode -> {
            put("scroll", node.scroll); put("spacing", node.spacing); put("arrangement", node.arrangement)
            put("horizontalAlignment", node.horizontalAlignment)
        }

        is RowNode -> {
            put("scroll", node.scroll); put("spacing", node.spacing); put("arrangement", node.arrangement)
            put("verticalAlignment", node.verticalAlignment)
        }

        is BoxNode -> put("contentAlignment", node.contentAlignment)

        is IfNode -> put("test", node.test)

        is ForeachNode -> {
            put("items", node.items)
            putRaw("item", node.itemName)
            putRaw("index", node.indexName)
            putRaw("key", node.keyAttribute)
        }

        is BranchNode -> putRaw("branch", node.branchTag)
    }

    node.boxStyle?.let { style ->
        put("width", style.width); put("height", style.height)
        put("padding", style.padding); put("paddingHorizontal", style.paddingHorizontal)
        put("paddingVertical", style.paddingVertical); put("backgroundColor", style.backgroundColor)
    }

    return out
}

/** `null` = 属性没写；`name=*` = 绑定槽位；`name="x"` = String 常量；`name` = 其它常量。 */
private fun render(name: String, value: AttrValue<*>?): String? = when {
    value == null -> null
    value is ValAttr<*> -> {
        val constant = value.constant
        when {
            value.path != null -> name
            constant is String -> "$name=\"$constant\""
            else -> name
        }
    }

    else -> "$name=*"
}
