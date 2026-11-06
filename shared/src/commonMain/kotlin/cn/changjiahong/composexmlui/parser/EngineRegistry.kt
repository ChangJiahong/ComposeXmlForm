package cn.changjiahong.composexmlui.parser

import androidx.compose.runtime.Composable
import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import kotlin.reflect.KClass

/**
 * 引擎注册表：解析与渲染**分别持有两张表**。
 *
 * - [nodes]：标签 → 怎么造节点。只需要校验时，注册这一张就够。
 * - [renderers]：节点类型 → 怎么画。可以整体替换内置渲染，也可以只为子类新增。
 *
 * 两者由 [register] 一起写入，避免「注册了节点忘了注册渲染器」；
 * 需要单独控制时直接操作 [nodes] / [renderers]。
 */
class EngineRegistry(
    val nodes: NodeRegistry = NodeRegistry(),
    val renderers: RendererRegistry = RendererRegistry(),
) {

    /** 同时登记两张表：内置组件和绝大多数自定义组件都用它。 */
    fun <T : Node> register(
        tag: String,
        nodeClass: KClass<T>,
        create: (NodeContext) -> T,
        renderer: NodeRenderer<T>,
    ) {
        nodes.register(tag, create)
        renderers.register(nodeClass, renderer)
    }

    /** 便捷重载：渲染逻辑直接写成 lambda。 */
    fun <T : Node> register(
        tag: String,
        nodeClass: KClass<T>,
        create: (NodeContext) -> T,
        render: @Composable (T) -> Unit,
    ) = register(tag, nodeClass, create, object : NodeRenderer<T>() {
        @Composable
        override fun Render(node: T) = render(node)
    })

    /** 摘掉一个标签。渲染器不随之删除：可能还有其它标签复用同一个节点类型。 */
    fun unregister(tag: String) = nodes.unregister(tag)

    fun hasTag(tag: String): Boolean = nodes.has(tag)

    fun tags(): Set<String> = nodes.tags()

    /** 解析 XML 并构建节点树（不做缓存，缓存由运行时负责）。 */
    fun parse(xml: String): Node = buildNode(XmlParser.parse(xml))

    internal fun buildNode(element: XmlElement): Node {
        // 先整体校验绑定语法：保证语法错误优先于「属性不被支持」报出，
        // 也因为表达式解析与属性归属无关，提前失败定位更准。
        element.attributes.forEach { (name, value) ->
            try {
                BindingParser.parseAttribute(value)
            } catch (e: XmlUiException) {
                throw XmlUiException(e.reason, tag = element.tag, attribute = name, line = element.line)
            }
        }

        val context = NodeContext(
            tag = element.tag,
            attributes = element.attributes,
            childSpecs = element.children,
            line = element.line,
            registry = this,
        )
        return try {
            val node = nodes.create(element.tag, context)
            context.ensureAllConsumed()
            node
        } catch (e: XmlUiException) {
            throw e.withTag(element.tag, element.line)
        }
    }

    /** 按节点类型分派渲染器。 */
    @Composable
    fun render(node: Node) {
        renderers.rendererFor(node).Render(node)
    }
}
