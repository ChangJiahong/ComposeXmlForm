package cn.changjiahong.composexmlform.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import cn.changjiahong.composexmlform.api.decl.Props
import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.render.LocalDiagnostics
import cn.changjiahong.composexmlform.engine.render.RenderChildren
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Value
import androidx.compose.foundation.layout.Box as LayoutBox
import androidx.compose.foundation.layout.Column as LayoutColumn
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Text as M3Text

/**
 * 内置组件渲染器。
 *
 * 每个渲染器都是一个**普通 Composable**：收一个已绑定本行现场的节点，`node.text` 就是
 * `String`、`node.value = x` 就是写回。没有 `NodeRenderer` 实现类、没有 `value()` /
 * `orElse()` / `rememberField()` —— 值的读取与写回都在节点的委托里做完了。
 *
 * 它们仍然只做一件事：**把值交给 Compose**。不持有数据、不管订阅 ——
 * 读 `MutableState` 即订阅，粒度精确到被读的那个字段。
 *
 * 与 Compose 同名的三个布局函数（`Column` / `Row` / `Box`）在这里就是组件渲染器，
 * 所以文件内改用 `LayoutColumn` / `LayoutRow` / `LayoutBox` 别名调用真正的布局。
 */

/** `Text`：只读属性。没写的保持 `Unspecified`，由主题决定。 */
@Composable
fun Text(node: TextNode) {
    val maxLines = node.maxLines?.toInt() ?: Int.MAX_VALUE

    M3Text(
        text = node.text,
        modifier = node.style.modifier(),
        color = node.color.toColor(),
        fontSize = node.fontSize?.sp ?: TextUnit.Unspecified,
        fontWeight = node.fontWeight.toFontWeight(),
        textAlign = node.textAlign.toTextAlign(),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** `Button`：`onClick` 是事件属性，读出来就是可空处理器。 */
@Composable
fun Button(node: ButtonNode) {
    val handler = node.onClick

    M3Button(
        onClick = { handler?.invoke() },
        modifier = node.style.modifier(),
        enabled = node.enabled,
    ) {
        M3Text(node.text)
    }
}

/** `TextField`：写了 `value="{{path}}"` 就双向绑定，没写就是非受控输入框。 */
@Composable
fun TextField(node: TextFieldNode) {
    OutlinedTextField(
        value = node.value,
        onValueChange = { node.value = it },
        modifier = node.style.modifier(),
        enabled = node.enabled,
        readOnly = node.readOnly,
        singleLine = node.singleLine,
        label = node.label?.let { text -> { M3Text(text) } },
        placeholder = node.placeholder?.let { text -> { M3Text(text) } },
    )
}

/**
 * `Select`：`options` 支持 `[{label, value}]` 或 `["a","b"]`。
 *
 * `value` 保留原始类型（不转 String）：`"1"` 与 `1` 在选中判断上不等价。
 */
@Composable
fun Select(node: SelectNode) {
    var expanded by remember { mutableStateOf(false) }

    val options = SelectOptions.parse(node.options)
    val selected = options.firstOrNull { equalOption(it.value, node.value) }

    LayoutColumn(modifier = node.style.modifier()) {
        node.label?.let { label ->
            M3Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LayoutBox {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = node.enabled,
            ) {
                M3Text(
                    text = selected?.label ?: node.placeholder,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { M3Text(option.label) },
                        onClick = {
                            expanded = false
                            node.value = option.value
                        },
                    )
                }
            }
        }
    }
}

/** `Column`：纵向排列，可选滚动。子节点全部渲染。 */
@Composable
fun Column(node: ColumnNode) {
    val spacing = node.spacing
    val arrangement = if (spacing != null) {
        Arrangement.spacedBy(spacing.dp)
    } else {
        node.props.chooseEnum(
            node.arrangement,
            "arrangement",
            COLUMN_ARRANGEMENT,
            Arrangement.Top,
        )
    }
    val alignment = node.props.chooseEnum(
        node.horizontalAlignment,
        "horizontalAlignment",
        HORIZONTAL_ALIGNMENT,
        Alignment.Start,
    )

    var modifier = node.style.modifier()
    if (node.scroll) modifier = modifier.verticalScroll(rememberScrollState())

    LayoutColumn(
        modifier = modifier,
        verticalArrangement = arrangement,
        horizontalAlignment = alignment,
    ) {
        RenderChildren(node)
    }
}

/** `Row`：横向排列，可选滚动。 */
@Composable
fun Row(node: RowNode) {
    val spacing = node.spacing
    val arrangement = if (spacing != null) {
        Arrangement.spacedBy(spacing.dp)
    } else {
        node.props.chooseEnum(node.arrangement, "arrangement", ROW_ARRANGEMENT, Arrangement.Start)
    }
    val alignment = node.props.chooseEnum(
        node.verticalAlignment,
        "verticalAlignment",
        VERTICAL_ALIGNMENT,
        Alignment.Top,
    )

    var modifier = node.style.modifier()
    if (node.scroll) modifier = modifier.horizontalScroll(rememberScrollState())

    LayoutRow(
        modifier = modifier,
        horizontalArrangement = arrangement,
        verticalAlignment = alignment,
    ) {
        RenderChildren(node)
    }
}

/** `Box`：层叠，按 `contentAlignment` 对齐。 */
@Composable
fun Box(node: BoxNode) {
    val alignment = node.props.chooseEnum(
        node.contentAlignment,
        "contentAlignment",
        BOX_ALIGNMENT,
        Alignment.TopStart,
    )

    LayoutBox(modifier = node.style.modifier(), contentAlignment = alignment) {
        RenderChildren(node)
    }
}

// ── 枚举属性映射 ──────────────────────────────────────────────
//
// 映射留在渲染器：`Alignment` / `Arrangement` 属于 Compose 排版体系，
// 不该出现在节点定义的类型里。

/**
 * 枚举属性的标准读法：认识就用映射值，不认识**报一条诊断再退回默认值**。
 *
 * 静默回退是最难查的一类问题（`alignment="centar"` 悄悄变成 `Start`），
 * 抛错又会白屏，所以两者都不取。`name` 只用于查诊断坐标 ——
 * 值仍然来自 `node.xxx`，这里不做第二次读取。
 */
@Composable
private fun <T> Props.chooseEnum(
    value: String?,
    name: String,
    mapping: Map<String, T>,
    default: T,
): T {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return default
    mapping[raw]?.let { return it }
    mapping[raw.lowercase()]?.let { return it }

    val site = siteOf(name) ?: return default
    val sink = LocalDiagnostics.current
    val diagnostic = Diagnostic(
        severity = Severity.Warning,
        code = Code.AttributeFallback,
        message = "『$name』的值 '$raw' 无效，可用：" +
            mapping.keys.sorted().joinToString(" / "),
        span = site.span,
        tag = site.tag,
        attribute = site.attribute,
    )
    SideEffect { sink.report(diagnostic) }
    return default
}

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
