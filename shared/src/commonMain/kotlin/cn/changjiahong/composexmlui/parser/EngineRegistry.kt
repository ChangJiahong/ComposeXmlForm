package cn.changjiahong.composexmlui.parser

import androidx.compose.runtime.Composable
import cn.changjiahong.composexmlui.binding.BindingExpression
import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.AttrEntry
import cn.changjiahong.composexmlui.node.AttrSite
import cn.changjiahong.composexmlui.node.AttrSource
import cn.changjiahong.composexmlui.node.Attrs
import cn.changjiahong.composexmlui.node.BranchNode
import cn.changjiahong.composexmlui.node.IfNode
import cn.changjiahong.composexmlui.node.LayoutNode
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import kotlin.reflect.KClass

/**
 * 引擎注册表：解析与渲染**分别持有两张表**。
 *
 * - [nodes]：标签 → 怎么造节点。只需要校验时，注册这一张就够。
 * - [renderers]：节点类型 → 怎么画。可以整体替换内置渲染，也可以只为子类新增。
 *
 * 两者由 [register] / [registerLayout] 一起写入，避免「注册了节点忘了注册渲染器」；
 * 需要单独控制时直接操作 [nodes] / [renderers]。
 */
class EngineRegistry(
    val nodes: NodeRegistry = NodeRegistry(),
    val renderers: RendererRegistry = RendererRegistry(),
) {

    /** 登记一个叶子组件（先建节点，子元素随后校验）。 */
    fun <T : Node> register(
        tag: String,
        nodeClass: KClass<T>,
        create: (Attrs) -> T,
        renderer: NodeRenderer<T>,
    ) {
        nodes.register(tag, create)
        renderers.register(nodeClass, renderer)
    }

    /** 登记一个容器组件（先建子节点，再建节点）。 */
    fun <T : LayoutNode> registerLayout(
        tag: String,
        nodeClass: KClass<T>,
        create: (Attrs, List<Node>) -> T,
        renderer: NodeRenderer<T>,
    ) {
        nodes.registerLayout(tag, create)
        renderers.register(nodeClass, renderer)
    }

    /** 便捷重载：渲染逻辑直接写成 lambda。 */
    fun <T : Node> register(
        tag: String,
        nodeClass: KClass<T>,
        create: (Attrs) -> T,
        render: @Composable (T) -> Unit,
    ) = register(tag, nodeClass, create, object : NodeRenderer<T>() {
        @Composable
        override fun Render(node: T) = render(node)
    })

    fun <T : LayoutNode> registerLayout(
        tag: String,
        nodeClass: KClass<T>,
        create: (Attrs, List<Node>) -> T,
        render: @Composable (T) -> Unit,
    ) = registerLayout(tag, nodeClass, create, object : NodeRenderer<T>() {
        @Composable
        override fun Render(node: T) = render(node)
    })

    /** 摘掉一个标签。渲染器不随之删除：可能还有其它标签复用同一个节点类型。 */
    fun unregister(tag: String) = nodes.unregister(tag)

    fun hasTag(tag: String): Boolean = nodes.has(tag)

    fun tags(): Set<String> = nodes.tags()

    /** 解析 XML 并构建节点树（不做缓存，缓存由运行时负责）。 */
    fun parse(xml: String): Node = buildNode(XmlParser.parse(xml))

    /**
     * 建节点：属性先整体解析成 [Attrs]，再交给节点工厂。
     *
     * 顺序是有讲究的：
     * 1. 分支标签（`Then` / `Else`）不是组件，直接造成 [BranchNode]；
     * 2. 先把所有属性的绑定语法解析一遍 —— 语法错误优先于「属性不被支持」报出；
     * 3. 容器先建 children 再建节点，叶子先建节点（子元素会在建完后被拒绝）；
     * 4. 工厂跑完再校验白名单。
     */
    internal fun buildNode(element: XmlElement, parent: XmlElement? = null): Node {
        val branchOwner = BRANCH_OWNERS[element.tag]
        if (branchOwner != null) {
            if (parent?.tag != branchOwner) {
                throw XmlUiException(
                    "<${element.tag}> 只能出现在 <$branchOwner> 内",
                    tag = element.tag,
                    line = element.line,
                )
            }
            return BranchNode(element.tag, element.children.map { buildNode(it, element) }, element.line)
        }

        val attrs = attrsOf(element)

        val node = try {
            if (nodes.isLayout(element.tag)) {
                nodes.createLayout(element.tag, attrs, element.children.map { buildNode(it, element) })
            } else {
                val leaf = nodes.createLeaf(element.tag, attrs)
                if (element.children.isNotEmpty()) {
                    throw XmlUiException(
                        "不允许包含子节点，实际包含 <${element.children.first().tag}>",
                        tag = element.tag,
                        line = element.line,
                    )
                }
                leaf
            }
        } catch (e: XmlUiException) {
            throw e.withTag(element.tag, element.line)
        }

        attrs.assertAllConsumed()
        return node
    }

    /** 属性名 → 未定型条目。类型要等节点声明才知道，所以这里只解析来源。 */
    private fun attrsOf(element: XmlElement): Attrs {
        val entries = LinkedHashMap<String, AttrEntry>(element.attributes.size)
        element.attributes.forEach { (name, raw) ->
            val site = AttrSite(element.tag, name, element.line)
            val source = try {
                when (val expression = BindingParser.parseAttribute(raw)) {
                    is BindingExpression.Literal -> AttrSource.Const(expression.raw)
                    is BindingExpression.Path -> AttrSource.Var(expression.path)
                }
            } catch (e: XmlUiException) {
                throw XmlUiException(e.reason, tag = element.tag, attribute = name, line = element.line)
            }
            entries[name] = AttrEntry(raw, source, site)
        }
        return Attrs(entries, element.tag, element.line)
    }

    /** 按节点类型分派渲染器。 */
    @Composable
    fun render(node: Node) {
        renderers.rendererFor(node).Render(node)
    }

    private companion object {
        /**
         * 分支标签 → 宿主组件。分支是 XML 语法的一部分，不是可注册的组件，
         * 所以它留在解析器里；扩展新的分支型组件时在这里加一行即可。
         */
        val BRANCH_OWNERS = mapOf(
            IfNode.THEN to IfNode.TAG,
            IfNode.ELSE to IfNode.TAG,
        )
    }
}
