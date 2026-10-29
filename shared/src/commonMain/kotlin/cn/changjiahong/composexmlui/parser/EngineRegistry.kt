package cn.changjiahong.composexmlui.parser

import androidx.compose.runtime.Composable
import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import kotlin.reflect.KClass

/** 所有节点都接受的通用的属性。 */
val COMMON_ATTRIBUTES: Set<String> = setOf(
    "id",
    "width",
    "height",
    "padding",
    "paddingHorizontal",
    "paddingVertical",
    "backgroundColor",
)

/**
 * 标签注册表：同时登记「节点工厂」与「渲染器」，以及该标签允许的属性集合。
 *
 * 三者一起注册是刻意的：只注册工厂会导致运行时找不到渲染器，
 * 只注册渲染器会导致解析期不认识标签。
 */
class EngineRegistry {

    private val entries = LinkedHashMap<String, TagEntry<*>>()

    fun <T : Node> register(
        tag: String,
        nodeClass: KClass<T>,
        allowedAttributes: Set<String> = emptySet(),
        factory: (NodeBuildContext) -> T,
        renderer: NodeRenderer<T>,
    ) {
        entries[tag] = TagEntry(tag, nodeClass, allowedAttributes, factory, renderer)
    }

    /** 便捷重载：直接传 @Composable 渲染 lambda。 */
    fun <T : Node> register(
        tag: String,
        nodeClass: KClass<T>,
        allowedAttributes: Set<String> = emptySet(),
        factory: (NodeBuildContext) -> T,
        render: @Composable (T) -> Unit,
    ) = register(tag, nodeClass, allowedAttributes, factory, object : NodeRenderer<T> {
        @Composable
        override fun Render(node: T) = render(node)
    })

    fun unregister(tag: String) {
        entries.remove(tag)
    }

    fun hasTag(tag: String): Boolean = entries.containsKey(tag)

    fun tags(): Set<String> = entries.keys.toSet()

    fun allowedAttributesOf(tag: String): Set<String>? = entries[tag]?.allowedAttributes

    /** 解析 XML 并构建节点树（不做缓存，缓存由运行时负责）。 */
    fun parse(xml: String): Node = buildNode(XmlParser.parse(xml))

    internal fun buildNode(element: XmlElement): Node {
        val entry = entries[element.tag] ?: throw XmlUiException(
            "未注册的组件标签 <${element.tag}>。已注册标签: ${entries.keys.sorted().joinToString(", ")}",
            tag = element.tag,
            line = element.line,
        )

        element.attributes.forEach { (name, value) ->
            try {
                BindingParser.parseAttribute(value)
            } catch (e: XmlUiException) {
                throw XmlUiException(e.reason, tag = element.tag, attribute = name, line = element.line)
            }
        }

        val unknown = element.attributes.keys.filterNot {
            it in entry.allowedAttributes || it in COMMON_ATTRIBUTES
        }
        if (unknown.isNotEmpty()) {
            val allowed = (entry.allowedAttributes + COMMON_ATTRIBUTES).sorted().joinToString(", ")
            throw XmlUiException(
                "不支持的属性 ${unknown.joinToString { "'$it'" }}。<${
                    element.tag
                }> 支持的属性: $allowed",
                tag = element.tag,
                attribute = unknown.first(),
                line = element.line,
            )
        }

        val context = NodeBuildContext(
            tag = element.tag,
            attributes = element.attributes,
            childSpecs = element.children,
            line = element.line,
            registry = this,
        )
        return try {
            entry.create(context)
        } catch (e: XmlUiException) {
            throw e.withTag(element.tag, element.line)
        }
    }

    @Composable
    fun render(node: Node) {
        val entry = entries[node.tag] ?: throw XmlUiException(
            "未注册的渲染器: <${node.tag}>",
            tag = node.tag,
            line = node.line,
        )
        entry.render(node)
    }
}

internal class TagEntry<T : Node>(
    val tag: String,
    private val nodeClass: KClass<T>,
    val allowedAttributes: Set<String>,
    private val factory: (NodeBuildContext) -> T,
    private val renderer: NodeRenderer<T>,
) {

    fun create(context: NodeBuildContext): Node = factory(context)

    @Composable
    fun render(node: Node) {
        if (!nodeClass.isInstance(node)) {
            throw XmlUiException(
                "节点类型不匹配: <$tag> 期望 ${nodeClass.simpleName}，实际 ${node::class.simpleName}",
                tag = node.tag,
                line = node.line,
            )
        }
        @Suppress("UNCHECKED_CAST")
        renderer.Render(node as T)
    }
}
