package cn.changjiahong.composexmlform.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * 属性字符串 → Compose 值的换算。
 *
 * 为什么换算放在渲染器，而不是像以前那样做成 `AttrType<Color>`：
 * `node` 包不许依赖 `androidx.compose.ui`（见包边界约定）。更重要的是**值形态**：
 * `color="{{c}}"` 做 `AttrValue<Color>` 时，数据里必须放一个 `Color` 实例，
 * 而 JSON 只能给字符串；改成 `AttrValue<String>` 后，`"#FF5722"` / `"red"` / `"{{c}}"` 三种写法
 * 走同一条路。
 *
 * **代价**：非法值不再在解析期报错，而是回退（`Color.Unspecified` / `null`）。
 * 这三类错误的后果只是「样式没生效」—— 不崩、不丢数据、开发期肉眼可见，
 * 为它加一套校验器等于把刚删掉的东西换个地方加回来。
 *
 * 全部定义在**可空接收者**上，配合 `attr.current().toColor()` 直接链式使用。
 */

/**
 * 颜色字面量 → [Color]；不认识 → null。
 *
 * 单独一个不依赖 Compose 运行时的实现，[toColor] 与单测都直接调它。
 */
fun parseColorLiteral(raw: String): Color? {
    if (raw.isEmpty()) return null
    if (raw.startsWith("#")) {
        val hex = raw.substring(1)
        val argb = when (hex.length) {
            3 -> "FF${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        val value = argb.lowercase().toLongOrNull(16) ?: return null
        return Color(value)
    }
    return NAMED_COLORS[raw.lowercase()]
}

/**
 * 颜色属性 → [Color]。认 `#RGB` / `#RRGGBB` / `#AARRGGBB` 与十几个常用色名，
 * 其余（含空串）回退 [Color.Unspecified]（等于没设颜色）。
 *
 * 不是 `@Composable`：这里没有任何 CompositionLocal 可读，只是一个纯换算。
 */
fun String?.toColor(): Color {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return Color.Unspecified
    return parseColorLiteral(raw) ?: Color.Unspecified
}

/** `normal` / `medium` / `semibold` / `bold`，或 100~900；不认识 → `null`（用主题默认字重）。 */
fun String?.toFontWeight(): FontWeight? = when (this?.trim()?.lowercase()) {
    null, "" -> null
    "normal", "regular" -> FontWeight.Normal
    "medium" -> FontWeight.Medium
    "semibold", "semi_bold" -> FontWeight.SemiBold
    "bold" -> FontWeight.Bold
    else -> {
        val number = this.trim().toIntOrNull() ?: return null
        if (number in 1..9) FontWeight(number * 100) else FontWeight(number)
    }
}

/** `start` / `center` / `end`；不认识 → `null`（用主题默认对齐）。 */
fun String?.toTextAlign(): TextAlign? = when (this?.trim()?.lowercase()) {
    "start", "left" -> TextAlign.Start
    "center" -> TextAlign.Center
    "end", "right" -> TextAlign.End
    else -> null
}

private val NAMED_COLORS: Map<String, Color> = mapOf(
    "black" to Color(0xFF000000),
    "white" to Color(0xFFFFFFFF),
    "transparent" to Color(0x00000000),
    "red" to Color(0xFFF44336),
    "green" to Color(0xFF4CAF50),
    "blue" to Color(0xFF2196F3),
    "yellow" to Color(0xFFFFEB3B),
    "orange" to Color(0xFFFF9800),
    "purple" to Color(0xFF9C27B0),
    "gray" to Color(0xFF9E9E9E),
    "grey" to Color(0xFF9E9E9E),
    "lightgray" to Color(0xFFF5F5F5),
    "lightgrey" to Color(0xFFF5F5F5),
)
