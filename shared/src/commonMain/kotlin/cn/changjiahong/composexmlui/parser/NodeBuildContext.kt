package cn.changjiahong.composexmlui.parser

import cn.changjiahong.composexmlui.binding.BindingExpression
import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node

/**
 * 构建节点时提供给工厂的上下文：只暴露「原始描述 + 建子节点」能力，
 * 工厂不接触 XML 解析器本身。
 */
class NodeBuildContext internal constructor(
    val tag: String,
    val attributes: Map<String, String>,
    val childSpecs: List<XmlElement>,
    val line: Int,
    private val registry: EngineRegistry,
) {

    /** 递归构建子节点（懒加载：叶子节点不会触发 children 构建）。 */
    val children: List<Node> by lazy { childSpecs.map { registry.buildNode(it) } }

    fun has(name: String): Boolean = attributes.containsKey(name)

    fun optional(name: String): String? = attributes[name]

    fun require(name: String): String =
        attributes[name] ?: fail("缺少必需属性 '$name'")

    /** 解析属性表达式，错误信息自动补齐标签与行号。 */
    fun expression(name: String): BindingExpression {
        val raw = require(name)
        return try {
            BindingParser.parseAttribute(raw)
        } catch (e: XmlUiException) {
            throw XmlUiException(e.reason, tag = tag, attribute = name, line = line)
        }
    }

    fun requireBinding(name: String): BindingExpression.Path {
        val expression = expression(name)
        return expression as? BindingExpression.Path
            ?: fail("属性 '$name' 必须是绑定表达式，例如 $name=\"{{value}}\"", name)
    }

    /** 构建某个子元素下的所有节点（用于 Then / Else 这类非注册标签）。 */
    fun buildChildrenOf(spec: XmlElement): List<Node> = spec.children.map { registry.buildNode(it) }

    fun ensureNoChildren() {
        if (childSpecs.isNotEmpty()) {
            fail("不允许包含子节点，实际包含 <${childSpecs.first().tag}>")
        }
    }

    fun ensureChildren() {
        if (childSpecs.isEmpty()) fail("至少需要一个子节点")
    }

    fun fail(reason: String, attribute: String? = null): Nothing =
        throw XmlUiException(reason, tag = tag, attribute = attribute, line = line)
}
