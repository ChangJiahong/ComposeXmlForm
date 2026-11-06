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
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.ButtonNode
import cn.changjiahong.composexmlui.node.SelectNode
import cn.changjiahong.composexmlui.node.TextFieldNode
import cn.changjiahong.composexmlui.node.TextNode
import cn.changjiahong.composexmlui.renderer.LocalActionRegistry
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import cn.changjiahong.composexmlui.renderer.asStateOr
import cn.changjiahong.composexmlui.renderer.containerModifier
import cn.changjiahong.composexmlui.renderer.current
import cn.changjiahong.composexmlui.store.ObservableData
import cn.changjiahong.composexmlui.store.ObservableList
import cn.changjiahong.composexmlui.store.ObservableMap

/**
 * Text：只读绑定。属性通过 [current] 读值，读 state 即订阅，
 * 因此字段变化时 Compose 会精确地只重组依赖该字段的组件。
 */
class TextNodeRenderer : NodeRenderer<TextNode>() {

    @Composable
    override fun Render(node: TextNode) {
        val maxLines = node.maxLines?.current() ?: Int.MAX_VALUE

        Text(
            text = node.value.current() ?: "",
            modifier = node.containerModifier(),
            color = node.color?.current() ?: Color.Unspecified,
            fontSize = node.fontSize?.current() ?: TextUnit.Unspecified,
            fontWeight = node.fontWeight?.current(),
            textAlign = node.textAlign?.current(),
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/** Button：点击时调用注册表里的无参动作。 */
class ButtonNodeRenderer : NodeRenderer<ButtonNode>() {

    @Composable
    override fun Render(node: ButtonNode) {
        val actions = LocalActionRegistry.current

        Button(
            onClick = { node.action?.let { actions.invoke(it.name) } },
            modifier = node.containerModifier(),
            enabled = node.enabled?.current() ?: true,
        ) {
            Text(node.text.current() ?: "")
        }
    }
}

/**
 * TextField：双向绑定。
 * [asStateOr] 在「写了 `value="{{path}}"`」时把作用域捕获进句柄（读订阅、写回数据），
 * 没写 `value` 时退化为局部 state —— 非受控输入框，能打字，只是不写回任何数据。
 */
class TextFieldNodeRenderer : NodeRenderer<TextFieldNode>() {

    @Composable
    override fun Render(node: TextFieldNode) {
        val value = node.value.asStateOr("")
        val labelText = node.label?.current()
        val placeholderText = node.placeholder?.current()

        OutlinedTextField(
            value = value.value ?: "",
            onValueChange = { newValue -> value.value = newValue },
            modifier = node.containerModifier(),
            enabled = node.enabled?.current() ?: true,
            readOnly = node.readOnly?.current() ?: false,
            singleLine = node.singleLine?.current() ?: true,
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
 * 选中后把 option.value 写回 `value` 的绑定路径。
 *
 * `value` / `options` 保留原始类型（不转 String）：`"1"` 与 `1` 在选中判断上不等价。
 */
class SelectNodeRenderer : NodeRenderer<SelectNode>() {

    @Composable
    override fun Render(node: SelectNode) {
        var expanded by remember { mutableStateOf(false) }
        val target = node.value.asStateOr(null)

        val options = SelectOptions.parse(node.options.current())
        val currentValue = target.value
        val selected = options.firstOrNull { it.value == currentValue }
            ?: options.firstOrNull {
                ObservableData.asString(it.value) == ObservableData.asString(currentValue)
            }
        val labelText = node.label?.current()
        val placeholder = node.placeholder?.current() ?: "请选择"

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
                                target.value = option.value
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
