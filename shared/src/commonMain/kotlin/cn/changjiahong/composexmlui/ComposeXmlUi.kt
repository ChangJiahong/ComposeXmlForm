package cn.changjiahong.composexmlui

import cn.changjiahong.composexmlui.binding.BindingParser
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
import cn.changjiahong.composexmlui.node.BranchNode
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
 * 内置组件注册。所有标签、属性白名单与渲染器都在这里集中声明，
 * 新增内置组件只需在此追加一条注册，不需要修改引擎其他部分。
 */
object DefaultComponents {

    fun register(registry: EngineRegistry) {

        // ---------------- 叶子组件 ----------------

        registry.register(
            tag = "Text",
            nodeClass = TextNode::class,
            allowedAttributes = setOf("value", "fontSize", "color", "maxLines", "fontWeight", "textAlign"),
            factory = { context ->
                context.ensureNoChildren()
                context.require("value")
                TextNode(context.attributes, context.line)
            },
            renderer = TextNodeRenderer(),
        )

        registry.register(
            tag = "Button",
            nodeClass = ButtonNode::class,
            allowedAttributes = setOf("text", "action", "enabled"),
            factory = { context ->
                context.ensureNoChildren()
                context.require("text")
                val action = context.optional("action")?.let { raw ->
                    BindingParser.parseAction(raw, tag = context.tag, line = context.line)
                }
                ButtonNode(context.attributes, action, context.line)
            },
            renderer = ButtonNodeRenderer(),
        )

        registry.register(
            tag = "TextField",
            nodeClass = TextFieldNode::class,
            allowedAttributes = setOf(
                "value", "label", "placeholder", "readOnly", "enabled", "singleLine",
            ),
            factory = { context ->
                context.ensureNoChildren()
                // 输入控件必须双向绑定，字面值没有意义
                context.requireBinding("value")
                TextFieldNode(context.attributes, context.line)
            },
            renderer = TextFieldNodeRenderer(),
        )

        registry.register(
            tag = "Select",
            nodeClass = SelectNode::class,
            allowedAttributes = setOf("options", "value", "label", "placeholder", "enabled"),
            factory = { context ->
                context.ensureNoChildren()
                context.requireBinding("options")
                SelectNode(context.attributes, context.line)
            },
            renderer = SelectNodeRenderer(),
        )

        // ---------------- 布局组件 ----------------

        registry.register(
            tag = "Column",
            nodeClass = ColumnNode::class,
            allowedAttributes = setOf("scroll", "spacing", "arrangement", "horizontalAlignment"),
            factory = { context -> ColumnNode(context.attributes, context.children, context.line) },
            renderer = ColumnNodeRenderer(),
        )

        registry.register(
            tag = "Row",
            nodeClass = RowNode::class,
            allowedAttributes = setOf("scroll", "spacing", "arrangement", "verticalAlignment"),
            factory = { context -> RowNode(context.attributes, context.children, context.line) },
            renderer = RowNodeRenderer(),
        )

        registry.register(
            tag = "Box",
            nodeClass = BoxNode::class,
            allowedAttributes = setOf("contentAlignment"),
            factory = { context -> BoxNode(context.attributes, context.children, context.line) },
            renderer = BoxNodeRenderer(),
        )

        // ---------------- 控制节点 ----------------

        registry.register(
            tag = "If",
            nodeClass = IfNode::class,
            allowedAttributes = setOf("test"),
            factory = { context ->
                val test = context.expression("test")
                var thenBranch: BranchNode? = null
                var elseBranch: BranchNode? = null
                context.childSpecs.forEach { child ->
                    when (child.tag) {
                        "Then" -> {
                            if (thenBranch != null) context.fail("只允许一个 <Then> 分支")
                            if (child.attributes.isNotEmpty()) {
                                context.fail("<Then> 不接受属性")
                            }
                            thenBranch = BranchNode("Then", context.buildChildrenOf(child), child.line)
                        }
                        "Else" -> {
                            if (elseBranch != null) context.fail("只允许一个 <Else> 分支")
                            if (child.attributes.isNotEmpty()) {
                                context.fail("<Else> 不接受属性")
                            }
                            elseBranch = BranchNode("Else", context.buildChildrenOf(child), child.line)
                        }
                        else -> context.fail(
                            "<If> 只允许包含 <Then> 与 <Else>，实际出现 <${child.tag}>",
                        )
                    }
                }
                IfNode(
                    attributes = context.attributes,
                    test = test,
                    thenBranch = thenBranch ?: context.fail("<If> 缺少必需的分支 <Then>"),
                    elseBranch = elseBranch,
                    line = context.line,
                )
            },
            renderer = IfNodeRenderer(),
        )

        registry.register(
            tag = "Foreach",
            nodeClass = ForeachNode::class,
            allowedAttributes = setOf("items", "item", "index", "key"),
            factory = { context ->
                context.ensureChildren()
                val items = context.requireBinding("items")
                val itemName = context.optional("item") ?: "item"
                if (!BindingParser.isIdentifier(itemName)) {
                    context.fail("item 必须是合法变量名，实际为 '$itemName'", "item")
                }
                val indexName = context.optional("index")?.also { name ->
                    if (!BindingParser.isIdentifier(name)) {
                        context.fail("index 必须是合法变量名，实际为 '$name'", "index")
                    }
                }
                ForeachNode(
                    attributes = context.attributes,
                    items = items,
                    itemName = itemName,
                    indexName = indexName,
                    keyAttribute = context.optional("key"),
                    children = context.children,
                    line = context.line,
                )
            },
            renderer = ForeachNodeRenderer(),
        )
    }
}
