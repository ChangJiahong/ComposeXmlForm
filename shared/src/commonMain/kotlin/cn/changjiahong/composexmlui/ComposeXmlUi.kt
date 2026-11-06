package cn.changjiahong.composexmlui

import cn.changjiahong.composexmlui.components.BoxNodeRenderer
import cn.changjiahong.composexmlui.components.ButtonNodeRenderer
import cn.changjiahong.composexmlui.components.ColumnNodeRenderer
import cn.changjiahong.composexmlui.components.RowNodeRenderer
import cn.changjiahong.composexmlui.components.SelectNodeRenderer
import cn.changjiahong.composexmlui.components.TextFieldNodeRenderer
import cn.changjiahong.composexmlui.components.TextNodeRenderer
import cn.changjiahong.composexmlui.controls.ForeachNodeRenderer
import cn.changjiahong.composexmlui.controls.IfNodeRenderer
import cn.changjiahong.composexmlui.node.BoxNode
import cn.changjiahong.composexmlui.node.ButtonNode
import cn.changjiahong.composexmlui.node.ColumnNode
import cn.changjiahong.composexmlui.node.ForeachNode
import cn.changjiahong.composexmlui.node.IfNode
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.node.RowNode
import cn.changjiahong.composexmlui.node.SelectNode
import cn.changjiahong.composexmlui.node.TextFieldNode
import cn.changjiahong.composexmlui.node.TextNode
import cn.changjiahong.composexmlui.parser.EngineRegistry

/**
 * 引擎门面。
 *
 * 使用方式：
 * ```
 * val registry = ComposeXmlUi.createRegistry()          // 新建（含内置组件）
 * registry.register("RatingBar", ...)                    // 注册自定义组件
 * DynamicLayout(xml = xml, registry = registry, ...)
 * ```
 */
object ComposeXmlUi {

    /** 全局共享注册表：内置组件 + 运行时动态注册的组件。 */
    val registry: EngineRegistry by lazy { createRegistry() }

    fun createRegistry(includeDefaults: Boolean = true): EngineRegistry =
        EngineRegistry().also { registry ->
            if (includeDefaults) DefaultComponents.register(registry)
        }

    /** 仅解析与校验，不渲染（供 IDE 预览、调试台、单元测试使用）。 */
    fun parse(xml: String, registry: EngineRegistry = this.registry): Node = registry.parse(xml)
}

/**
 * 内置组件注册。
 *
 * 内置组件走的是和第三方扩展**完全相同**的注册路径，没有任何特权：
 * 覆盖 `"Text"` 的标签注册就能换掉内置节点，覆盖 `TextNode::class` 的渲染器
 * 就能换掉内置画法，两者互不影响。
 *
 * 属性白名单由节点实际读取了哪些属性自动推导（`NodeContext.ensureAllConsumed`），
 * 因此新增属性只需要在节点构造参数里加一行，不会有第二处需要同步。
 */
object DefaultComponents {

    fun register(registry: EngineRegistry) {

        // ---------------- 叶子组件 ----------------

        registry.register("Text", TextNode::class, ::TextNode, TextNodeRenderer())
        registry.register("Button", ButtonNode::class, ::ButtonNode, ButtonNodeRenderer())
        registry.register("TextField", TextFieldNode::class, ::TextFieldNode, TextFieldNodeRenderer())
        registry.register("Select", SelectNode::class, ::SelectNode, SelectNodeRenderer())

        // ---------------- 布局组件 ----------------

        registry.register("Column", ColumnNode::class, ::ColumnNode, ColumnNodeRenderer())
        registry.register("Row", RowNode::class, ::RowNode, RowNodeRenderer())
        registry.register("Box", BoxNode::class, ::BoxNode, BoxNodeRenderer())

        // ---------------- 控制节点 ----------------

        registry.register("If", IfNode::class, ::IfNode, IfNodeRenderer())
        registry.register("Foreach", ForeachNode::class, ::ForeachNode, ForeachNodeRenderer())
    }
}
