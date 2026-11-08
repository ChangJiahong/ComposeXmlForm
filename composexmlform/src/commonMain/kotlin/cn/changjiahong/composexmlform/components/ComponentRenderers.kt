package cn.changjiahong.composexmlform.components

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.ButtonNode
import cn.changjiahong.composexmlform.node.SelectNode
import cn.changjiahong.composexmlform.node.TextFieldNode
import cn.changjiahong.composexmlform.node.TextNode
import cn.changjiahong.composexmlform.renderer.fire
import cn.changjiahong.composexmlform.renderer.NodeRenderer
import cn.changjiahong.composexmlform.renderer.get
import cn.changjiahong.composexmlform.renderer.provideDelegate
import cn.changjiahong.composexmlform.renderer.containerModifier
import cn.changjiahong.composexmlform.renderer.current
import cn.changjiahong.composexmlform.renderer.toColor
import cn.changjiahong.composexmlform.renderer.toFontWeight
import cn.changjiahong.composexmlform.renderer.toTextAlign
import cn.changjiahong.composexmlform.store.ObservableData
import cn.changjiahong.composexmlform.store.ObservableList
import cn.changjiahong.composexmlform.store.ObservableMap

/**
 * Text：只读绑定。属性通过 [current] 读值，读 state 即订阅，
 * 因此字段变化时 Compose 会精确地只重组依赖该字段的组件。
 *
 * `fontSize` 是 `Float`（数值 = sp），`color` / `fontWeight` / `textAlign` 是字符串 ——
 * 换算成 Compose 值在渲染器（见 [toColor] / [toFontWeight] / [toTextAlign]）。
 */
class TextNodeRenderer : NodeRenderer<TextNode> {

    @Composable
    override fun Render(node: TextNode) {
        // 有意义的缺省值用 get(默认值)；「没设」本身有含义的（颜色 / 字号 / 字重 / 对齐）
        // 保持 moment 直读，因为它们的「缺省」不是某个具体值，而是 Color/TextUnit 的 Unspecified。
        val maxLines = node.maxLines.get(Int.MAX_VALUE)

        Text(
            text = node.value.get(""),
            modifier = node.style.containerModifier(),
            color = node.color?.current().toColor(),
            fontSize = node.fontSize?.current()?.sp ?: TextUnit.Unspecified,
            fontWeight = node.fontWeight?.current().toFontWeight(),
            textAlign = node.textAlign?.current().toTextAlign(),
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/** Button：`action` 是普通属性槽位，`fire()` 给出可点击函数（内含协程与异常上报）。 */
class ButtonNodeRenderer : NodeRenderer<ButtonNode> {

    @Composable
    override fun Render(node: ButtonNode) {
        Button(
            onClick = node.action.fire(),
            modifier = node.style.containerModifier(),
            enabled = node.enabled.get(true),
        ) {
            Text(node.text.get(""))
        }
    }
}

/**
 * TextField：双向绑定。
 *
 * `var value by node.value` 把「属性 + 作用域」在**声明处**结合：写了
 * `value="{{path}}"` 就是读订阅、写回数据；没写就退化为局部 state（非受控输入框，
 * 能打字，只是不写回任何数据）。`by` 只能用在这一行，`onValueChange` 里普通赋值即可。
 */
class TextFieldNodeRenderer : NodeRenderer<TextFieldNode> {

    @Composable
    override fun Render(node: TextFieldNode) {
        var value by node.value
        val labelText = node.label?.current()
        val placeholderText = node.placeholder?.current()

        OutlinedTextField(
            value = value ?: "",
            onValueChange = { newValue -> value = newValue },
            modifier = node.style.containerModifier(),
            enabled = node.enabled.get(true),
            readOnly = node.readOnly.get(false),
            singleLine = node.singleLine.get(true),
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
class SelectNodeRenderer : NodeRenderer<SelectNode> {

    @Composable
    override fun Render(node: SelectNode) {
        var expanded by remember { mutableStateOf(false) }
        var currentValue by node.value

        val options = SelectOptions.parse(node.options.current())
        val selected = options.firstOrNull { it.value == currentValue }
            ?: options.firstOrNull {
                ObservableData.asString(it.value) == ObservableData.asString(currentValue)
            }
        val labelText = node.label?.current()
        val placeholder = node.placeholder.get("请选择")

        Column(modifier = node.style.containerModifier()) {
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
                                currentValue = option.value
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
