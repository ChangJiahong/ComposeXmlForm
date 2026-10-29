package cn.changjiahong.composexmlui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import cn.changjiahong.composexmlui.binding.BindingExpression
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.ButtonNode
import cn.changjiahong.composexmlui.node.SelectNode
import cn.changjiahong.composexmlui.node.TextFieldNode
import cn.changjiahong.composexmlui.node.TextNode
import cn.changjiahong.composexmlui.renderer.Attrs
import cn.changjiahong.composexmlui.renderer.LocalActionRegistry
import cn.changjiahong.composexmlui.renderer.LocalStoreOwner
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import cn.changjiahong.composexmlui.renderer.containerModifier
import cn.changjiahong.composexmlui.store.ObservableData
import cn.changjiahong.composexmlui.store.ObservableList
import cn.changjiahong.composexmlui.store.ObservableMap

/**
 * Text：只读绑定。`resolveString` 通过 Store 的可观察状态读取，
 * 因此字段变化时 Compose 会精确地只重组依赖该字段的文本。
 */
class TextNodeRenderer : NodeRenderer<TextNode> {

    @Composable
    override fun Render(node: TextNode) {
        val owner = LocalStoreOwner.current
        val text = owner.readString(node.expression("value"))
        val maxLines = Attrs.int(node, "maxLines") ?: Int.MAX_VALUE

        Text(
            text = text,
            modifier = node.containerModifier(),
            color = Attrs.color(node, "color") ?: Color.Unspecified,
            fontSize = Attrs.fontSize(node, "fontSize") ?: TextUnit.Unspecified,
            fontWeight = Attrs.fontWeight(node, "fontWeight"),
            textAlign = Attrs.textAlign(node, "textAlign"),
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/** Button：点击时调用注册表里的无参动作。 */
class ButtonNodeRenderer : NodeRenderer<ButtonNode> {

    @Composable
    override fun Render(node: ButtonNode) {
        val owner = LocalStoreOwner.current
        val actions = LocalActionRegistry.current
        val label = owner.readString(node.expression("text"))

        Button(
            onClick = { node.action?.let { actions.invoke(it.name) } },
            modifier = node.containerModifier(),
            enabled = Attrs.bool(node, "enabled") ?: true,
        ) {
            Text(label)
        }
    }
}

/**
 * TextField：双向绑定。
 * 读取走统一绑定读取（订阅字段），写入走 `owner.write`（写回原始数据路径）。
 */
class TextFieldNodeRenderer : NodeRenderer<TextFieldNode> {

    @Composable
    override fun Render(node: TextFieldNode) {
        val owner = LocalStoreOwner.current
        val valueExpression = node.expressionOrNull("value")
        val current = valueExpression?.let { ObservableData.asString(owner.read(it)) } ?: ""
        val labelText = node.expressionOrNull("label")?.let { owner.readString(it) }
        val placeholderText = node.expressionOrNull("placeholder")?.let { owner.readString(it) }

        OutlinedTextField(
            value = current,
            onValueChange = { newValue ->
                if (valueExpression is BindingExpression.Path) {
                    owner.write(valueExpression.path, newValue)
                }
            },
            modifier = node.containerModifier(),
            enabled = Attrs.bool(node, "enabled") ?: true,
            readOnly = Attrs.bool(node, "readOnly") ?: false,
            singleLine = Attrs.bool(node, "singleLine") ?: true,
            label = if (labelText != null) {
                { Text(labelText) }
            } else null,
            placeholder = if (placeholderText != null) {
                { Text(placeholderText) }
            } else null,
        )
    }
}

/**
 * Select：`options` 支持 List<Map>（label/value）或 List<String>。
 * 选中后把 option.value 写回 `value` 绑定路径。
 */
class SelectNodeRenderer : NodeRenderer<SelectNode> {

    @Composable
    override fun Render(node: SelectNode) {
        val owner = LocalStoreOwner.current
        var expanded by remember { mutableStateOf(false) }

        val options = SelectOptions.parse(owner.read(node.expression("options")))
        val valueExpression = node.expressionOrNull("value")
        val currentValue = valueExpression?.let { ObservableData.unwrap(owner.read(it)) }
        val selected = options.firstOrNull { it.value == currentValue }
            ?: options.firstOrNull {
                ObservableData.asString(it.value) == ObservableData.asString(currentValue)
            }
        val labelText = node.expressionOrNull("label")?.let { owner.readString(it) }
        val placeholder = node.raw("placeholder") ?: "请选择"

        Column(modifier = node.containerModifier()) {
            if (labelText != null) {
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = selected?.label ?: placeholder,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                expanded = false
                                if (valueExpression is BindingExpression.Path) {
                                    owner.write(valueExpression.path, option.value)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

internal class SelectOption(val label: String, val value: Any?)

internal object SelectOptions {

    fun parse(raw: Any?): List<SelectOption> = when (raw) {
        null -> emptyList()
        is ObservableList -> (0 until raw.size).map { parseItem(raw.get(it)) }
        is List<*> -> raw.map { parseItem(it) }
        else -> throw XmlUiException(
            "options 必须是列表，实际为 ${raw::class.simpleName}",
            tag = "Select",
            attribute = "options",
        )
    }

    private fun parseItem(item: Any?): SelectOption = when (item) {
        is ObservableMap -> {
            val label = item.get("label") ?: item.get("text") ?: item.get("name") ?: item.get("value")
            val value = if (item.contains("value")) item.get("value") else item.toPlain()
            SelectOption(ObservableData.asString(label), value)
        }
        is Map<*, *> -> {
            val label = item["label"] ?: item["text"] ?: item["name"] ?: item["value"]
            val value = if (item.containsKey("value")) item["value"] else item
            SelectOption(ObservableData.asString(label), value)
        }
        else -> SelectOption(ObservableData.asString(item), item)
    }
}
