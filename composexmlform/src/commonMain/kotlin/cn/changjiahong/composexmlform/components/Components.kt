package cn.changjiahong.composexmlform.components

import cn.changjiahong.composexmlform.api.ComponentModule
import cn.changjiahong.composexmlform.api.EngineRegistry
import cn.changjiahong.composexmlform.engine.node.Attr
import cn.changjiahong.composexmlform.engine.node.AttrDef
import cn.changjiahong.composexmlform.engine.node.ChildRule
import cn.changjiahong.composexmlform.engine.value.AnyAdapter
import cn.changjiahong.composexmlform.engine.value.FloatAdapter

/**
 * 内置组件的属性声明与注册。
 *
 * 属性名只在这些对象里出现一次：节点构造函数从这里取，白名单也从这里生成，
 * 不存在「两处清单不一致」的可能。
 *
 * 枚举型属性（`arrangement` / `alignment` / `fontWeight`）一律声明成 `String`：
 * Compose 的排版类型不该进节点定义，映射留在渲染器（见 [ColumnNodeRenderer]）。
 */
object TextAttrs {
    val value: AttrDef<String> = Attr.string("value")
    val fontSize: AttrDef<Float> = Attr.of("fontSize", FloatAdapter)
    val color: AttrDef<String> = Attr.string("color")
    val maxLines: AttrDef<Long> = Attr.int("maxLines")
    val fontWeight: AttrDef<String> = Attr.string("fontWeight")
    val textAlign: AttrDef<String> = Attr.string("textAlign")

    val All: List<AttrDef<*>> =
        listOf(value, fontSize, color, maxLines, fontWeight, textAlign) + StyleAttrs.All
}

object ButtonAttrs {
    val text: AttrDef<String> = Attr.string("text")
    val onClick: AttrDef<cn.changjiahong.composexmlform.engine.value.Function> = Attr.func("onClick")
    val enabled: AttrDef<Boolean> = Attr.bool("enabled", default = true)

    val All: List<AttrDef<*>> = listOf(text, onClick, enabled) + StyleAttrs.All
}

object TextFieldAttrs {
    /** 可写槽位：只接受 `{{path}}`，渲染层据此挂 `onValueChange`。 */
    val value: AttrDef<String> = Attr.varString("value")
    val label: AttrDef<String> = Attr.string("label")
    val placeholder: AttrDef<String> = Attr.string("placeholder")
    val readOnly: AttrDef<Boolean> = Attr.bool("readOnly", default = false)
    val enabled: AttrDef<Boolean> = Attr.bool("enabled", default = true)
    val singleLine: AttrDef<Boolean> = Attr.bool("singleLine", default = true)

    val All: List<AttrDef<*>> =
        listOf(value, label, placeholder, readOnly, enabled, singleLine) + StyleAttrs.All
}

object SelectAttrs {
    val options: AttrDef<cn.changjiahong.composexmlform.engine.value.Value> =
        Attr.of("options", AnyAdapter)

    val value: AttrDef<cn.changjiahong.composexmlform.engine.value.Value> =
        Attr.varOf("value", AnyAdapter)

    val label: AttrDef<String> = Attr.string("label")
    val placeholder: AttrDef<String> = Attr.string("placeholder", default = "请选择")
    val enabled: AttrDef<Boolean> = Attr.bool("enabled", default = true)

    val All: List<AttrDef<*>> = listOf(options, value, label, placeholder, enabled) + StyleAttrs.All
}

object ColumnAttrs {
    val scroll: AttrDef<Boolean> = Attr.bool("scroll", default = false)
    val spacing: AttrDef<Float> = Attr.of("spacing", FloatAdapter)
    val arrangement: AttrDef<String> = Attr.string("arrangement")
    val horizontalAlignment: AttrDef<String> = Attr.string("horizontalAlignment")

    val All: List<AttrDef<*>> =
        listOf(scroll, spacing, arrangement, horizontalAlignment) + StyleAttrs.All
}

object RowAttrs {
    val scroll: AttrDef<Boolean> = Attr.bool("scroll", default = false)
    val spacing: AttrDef<Float> = Attr.of("spacing", FloatAdapter)
    val arrangement: AttrDef<String> = Attr.string("arrangement")
    val verticalAlignment: AttrDef<String> = Attr.string("verticalAlignment")

    val All: List<AttrDef<*>> = listOf(scroll, spacing, arrangement, verticalAlignment) + StyleAttrs.All
}

object BoxAttrs {
    val contentAlignment: AttrDef<String> = Attr.string("contentAlignment")

    val All: List<AttrDef<*>> = listOf(contentAlignment) + StyleAttrs.All
}

/**
 * 内置组件注册。
 *
 * 走的是和第三方扩展**完全相同**的路径：覆盖 `"Text"` 的注册就能换掉内置节点，
 * `registry.repaint(TextNode::class, …)` 就能换掉内置画法，两者互不影响。
 *
 * `Text` / `Button` / `TextField` / `Select` 是叶子（`ChildRule.None`）；
 * `Column` / `Row` / `Box` 接受任意子节点。
 * `If` / `Foreach` 不在其中 —— 它们是引擎结构节点，`EngineRegistry` 一开始就装好了。
 */
val DefaultComponents: ComponentModule = ComponentModule { registry ->
    installLeaves(registry)
    installLayouts(registry)
}

private fun installLeaves(registry: EngineRegistry) {
    registry.register(
        nodeClass = TextNode::class,
        tag = "Text",
        attributes = TextAttrs.All,
        create = { id, attrs, _ ->
            TextNode(
                id = id,
                text = attrs.attr(TextAttrs.value),
                fontSize = attrs.attr(TextAttrs.fontSize),
                color = attrs.attr(TextAttrs.color),
                fontWeight = attrs.attr(TextAttrs.fontWeight),
                textAlign = attrs.attr(TextAttrs.textAlign),
                maxLines = attrs.attr(TextAttrs.maxLines),
                style = styleOf(attrs),
            )
        },
        renderer = TextNodeRenderer(),
    )

    registry.register(
        nodeClass = ButtonNode::class,
        tag = "Button",
        attributes = ButtonAttrs.All,
        create = { id, attrs, _ ->
            ButtonNode(
                id = id,
                text = attrs.attr(ButtonAttrs.text),
                onClick = attrs.attr(ButtonAttrs.onClick),
                enabled = attrs.attr(ButtonAttrs.enabled),
                style = styleOf(attrs),
            )
        },
        renderer = ButtonNodeRenderer(),
    )

    registry.register(
        nodeClass = TextFieldNode::class,
        tag = "TextField",
        attributes = TextFieldAttrs.All,
        create = { id, attrs, _ ->
            TextFieldNode(
                id = id,
                value = attrs.attr(TextFieldAttrs.value),
                label = attrs.attr(TextFieldAttrs.label),
                placeholder = attrs.attr(TextFieldAttrs.placeholder),
                readOnly = attrs.attr(TextFieldAttrs.readOnly),
                enabled = attrs.attr(TextFieldAttrs.enabled),
                singleLine = attrs.attr(TextFieldAttrs.singleLine),
                style = styleOf(attrs),
            )
        },
        renderer = TextFieldNodeRenderer(),
    )

    registry.register(
        nodeClass = SelectNode::class,
        tag = "Select",
        attributes = SelectAttrs.All,
        create = { id, attrs, _ ->
            SelectNode(
                id = id,
                options = attrs.attr(SelectAttrs.options),
                value = attrs.attr(SelectAttrs.value),
                label = attrs.attr(SelectAttrs.label),
                placeholder = attrs.attr(SelectAttrs.placeholder),
                enabled = attrs.attr(SelectAttrs.enabled),
                style = styleOf(attrs),
            )
        },
        renderer = SelectNodeRenderer(),
    )
}

private fun installLayouts(registry: EngineRegistry) {
    registry.register(
        nodeClass = ColumnNode::class,
        tag = "Column",
        attributes = ColumnAttrs.All,
        childRule = ChildRule.Any,
        create = { id, attrs, children ->
            ColumnNode(
                id = id,
                scroll = attrs.attr(ColumnAttrs.scroll),
                spacing = attrs.attr(ColumnAttrs.spacing),
                arrangement = attrs.attr(ColumnAttrs.arrangement),
                horizontalAlignment = attrs.attr(ColumnAttrs.horizontalAlignment),
                style = styleOf(attrs),
                children = children,
            )
        },
        renderer = ColumnNodeRenderer(),
    )

    registry.register(
        nodeClass = RowNode::class,
        tag = "Row",
        attributes = RowAttrs.All,
        childRule = ChildRule.Any,
        create = { id, attrs, children ->
            RowNode(
                id = id,
                scroll = attrs.attr(RowAttrs.scroll),
                spacing = attrs.attr(RowAttrs.spacing),
                arrangement = attrs.attr(RowAttrs.arrangement),
                verticalAlignment = attrs.attr(RowAttrs.verticalAlignment),
                style = styleOf(attrs),
                children = children,
            )
        },
        renderer = RowNodeRenderer(),
    )

    registry.register(
        nodeClass = BoxNode::class,
        tag = "Box",
        attributes = BoxAttrs.All,
        childRule = ChildRule.Any,
        create = { id, attrs, children ->
            BoxNode(
                id = id,
                contentAlignment = attrs.attr(BoxAttrs.contentAlignment),
                style = styleOf(attrs),
                children = children,
            )
        },
        renderer = BoxNodeRenderer(),
    )
}
