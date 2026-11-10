package cn.changjiahong.composexmlform.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlform.api.rememberField
import cn.changjiahong.composexmlform.engine.render.NodeRenderer
import cn.changjiahong.composexmlform.engine.render.RenderChildren
import cn.changjiahong.composexmlform.engine.render.choose
import cn.changjiahong.composexmlform.engine.render.orElse
import cn.changjiahong.composexmlform.engine.render.rememberHandler
import cn.changjiahong.composexmlform.engine.render.value
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 内置组件渲染器。
 *
 * 每个渲染器都只做一件事：**把槽位读成值，交给 Compose**。它们不持有数据、
 * 不写数据（除了可写槽位通过 `rememberField` 的回调间接写）、不管订阅 ——
 * 读 `MutableState` 即订阅，粒度精确到被读的那个字段。
 */

/** `Text`：只读绑定。没写的属性保持 `Unspecified`，由主题决定。 */
class TextNodeRenderer : NodeRenderer<TextNode> {

    @Composable
    override fun Render(node: TextNode) {
        val maxLines = node.maxLines.value()?.toInt() ?: Int.MAX_VALUE

        Text(
            text = node.text.orElse(""),
            modifier = node.style.modifier(),
            color = node.color.value().toColor(),
            fontSize = node.fontSize.value()?.sp ?: TextUnit.Unspecified,
            fontWeight = node.fontWeight.value().toFontWeight(),
            textAlign = node.textAlign.value().toTextAlign(),
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/** `Button`：`onClick` 是函数槽位，读成 [cn.changjiahong.composexmlform.engine.render.EventHandler]。 */
class ButtonNodeRenderer : NodeRenderer<ButtonNode> {

    @Composable
    override fun Render(node: ButtonNode) {
        val handler = node.onClick.rememberHandler()

        Button(
            onClick = { handler?.invoke() },
            modifier = node.style.modifier(),
            enabled = node.enabled.orElse(true),
        ) {
            Text(node.text.orElse(""))
        }
    }
}

/** `TextField`：写了 `value="{{path}}"` 就双向绑定，没写就是非受控输入框。 */
class TextFieldNodeRenderer : NodeRenderer<TextFieldNode> {

    @Composable
    override fun Render(node: TextFieldNode) {
        val (value, onChange) = node.value.rememberField<String>()
        val label = node.label.value()
        val placeholder = node.placeholder.value()

        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onChange,
            modifier = node.style.modifier(),
            enabled = node.enabled.orElse(true),
            readOnly = node.readOnly.orElse(false),
            singleLine = node.singleLine.orElse(true),
            label = label?.let { text -> { Text(text) } },
            placeholder = placeholder?.let { text -> { Text(text) } },
        )
    }
}

/**
 * `Select`：`options` 支持 `[{label, value}]` 或 `["a","b"]`。
 *
 * `value` 保留原始类型（不转 String）：`"1"` 与 `1` 在选中判断上不等价。
 */
class SelectNodeRenderer : NodeRenderer<SelectNode> {

    @Composable
    override fun Render(node: SelectNode) {
        var expanded by remember { mutableStateOf(false) }
        val (value, onChange) = node.value.rememberField<Value>()

        val options = SelectOptions.parse(node.options.value())
        val selected = options.firstOrNull { equalOption(it.value, value) }
        val label = node.label.value()
        val placeholder = node.placeholder.orElse("请选择")

        Column(modifier = node.style.modifier()) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = node.enabled.orElse(true),
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
                                onChange(option.value)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** `Column`：纵向排列，可选滚动。子节点全部渲染。 */
class ColumnNodeRenderer : NodeRenderer<ColumnNode> {

    @Composable
    override fun Render(node: ColumnNode) {
        val spacing = node.spacing.value()
        val arrangement = if (spacing != null) {
            Arrangement.spacedBy(spacing.dp)
        } else {
            node.arrangement.choose(COLUMN_ARRANGEMENT, Arrangement.Top)
        }
        val alignment = node.horizontalAlignment.choose(HORIZONTAL_ALIGNMENT, Alignment.Start)

        var modifier = node.style.modifier()
        if (node.scroll.orElse(false)) modifier = modifier.verticalScroll(rememberScrollState())

        Column(
            modifier = modifier,
            verticalArrangement = arrangement,
            horizontalAlignment = alignment,
        ) {
            RenderChildren(node)
        }
    }
}

/** `Row`：横向排列，可选滚动。 */
class RowNodeRenderer : NodeRenderer<RowNode> {

    @Composable
    override fun Render(node: RowNode) {
        val spacing = node.spacing.value()
        val arrangement = if (spacing != null) {
            Arrangement.spacedBy(spacing.dp)
        } else {
            node.arrangement.choose(ROW_ARRANGEMENT, Arrangement.Start)
        }
        val alignment = node.verticalAlignment.choose(VERTICAL_ALIGNMENT, Alignment.Top)

        var modifier = node.style.modifier()
        if (node.scroll.orElse(false)) modifier = modifier.horizontalScroll(rememberScrollState())

        Row(
            modifier = modifier,
            horizontalArrangement = arrangement,
            verticalAlignment = alignment,
        ) {
            RenderChildren(node)
        }
    }
}

/** `Box`：层叠，按 `contentAlignment` 对齐。 */
class BoxNodeRenderer : NodeRenderer<BoxNode> {

    @Composable
    override fun Render(node: BoxNode) {
        val alignment = node.contentAlignment.choose(BOX_ALIGNMENT, Alignment.TopStart)

        Box(modifier = node.style.modifier(), contentAlignment = alignment) {
            RenderChildren(node)
        }
    }
}

// ── 枚举属性映射 ──────────────────────────────────────────────
//
// 映射留在渲染器：`Alignment` / `Arrangement` 属于 Compose 排版体系，
// 不该出现在节点定义的类型里。

private val COLUMN_ARRANGEMENT: Map<String, Arrangement.Vertical> = mapOf(
    "top" to Arrangement.Top,
    "center" to Arrangement.Center,
    "bottom" to Arrangement.Bottom,
    "spacebetween" to Arrangement.SpaceBetween,
    "spacearound" to Arrangement.SpaceAround,
    "spaceevenly" to Arrangement.SpaceEvenly,
)

private val ROW_ARRANGEMENT: Map<String, Arrangement.Horizontal> = mapOf(
    "start" to Arrangement.Start,
    "center" to Arrangement.Center,
    "end" to Arrangement.End,
    "spacebetween" to Arrangement.SpaceBetween,
    "spacearound" to Arrangement.SpaceAround,
    "spaceevenly" to Arrangement.SpaceEvenly,
)

private val HORIZONTAL_ALIGNMENT: Map<String, Alignment.Horizontal> = mapOf(
    "start" to Alignment.Start,
    "center" to Alignment.CenterHorizontally,
    "end" to Alignment.End,
)

private val VERTICAL_ALIGNMENT: Map<String, Alignment.Vertical> = mapOf(
    "top" to Alignment.Top,
    "center" to Alignment.CenterVertically,
    "bottom" to Alignment.Bottom,
)

private val BOX_ALIGNMENT: Map<String, Alignment> = mapOf(
    "center" to Alignment.Center,
    "topstart" to Alignment.TopStart,
    "topcenter" to Alignment.TopCenter,
    "topend" to Alignment.TopEnd,
    "centerstart" to Alignment.CenterStart,
    "centerend" to Alignment.CenterEnd,
    "bottomstart" to Alignment.BottomStart,
    "bottomcenter" to Alignment.BottomCenter,
    "bottomend" to Alignment.BottomEnd,
)

// ── Select 的选项解析 ─────────────────────────────────────────

internal class SelectOption(val label: String, val value: Value)

/**
 * `options` 的三种形态：对象数组（`label` / `text` / `name` + `value`）、标量数组、空。
 *
 * 不认识的一律当空列表 + 不崩：`options` 来自数据，脏数据不该让整页白屏。
 * 真正该报的是「写错了属性名」，那在解析期就报过了。
 */
internal object SelectOptions {

    fun parse(raw: Value): List<SelectOption> = when (raw) {
        null, Absent -> emptyList()
        is DataArray -> (0 until raw.size).map { parseItem(raw.get(it)) }
        is List<*> -> raw.map { parseItem(it) }
        else -> emptyList()
    }

    private fun parseItem(item: Value): SelectOption = when (item) {
        is DataObject -> {
            val label = firstPresent(item, "label", "text", "name", "value")
            val value = item.member("value").takeUnless { it === Absent } ?: item.snapshot()
            SelectOption(display(label), value)
        }

        is Map<*, *> -> {
            val label = item["label"] ?: item["text"] ?: item["name"] ?: item["value"]
            val value = if (item.containsKey("value")) item["value"] else item
            SelectOption(display(label), value)
        }

        else -> SelectOption(display(item), item)
    }

    private fun firstPresent(obj: DataObject, vararg names: String): Value {
        for (name in names) {
            val value = obj.member(name)
            if (value !== Absent) return value
        }
        return null
    }

    private fun display(value: Value): String = when (value) {
        null, Absent -> ""
        is String -> value
        is Long -> value.toString()
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }
}

/** 选中判定：`1` 与 `1.0` 视为同一个选项（与脚本 `==` 的口径一致）。 */
private fun equalOption(a: Value, b: Value): Boolean {
    if (a === b) return true
    if (a is Long && b is Double) return a.toDouble() == b
    if (a is Double && b is Long) return a == b.toDouble()
    if (a is String && b is String) return a == b
    if (a is Long && b is Long) return a == b
    return a == b
}
