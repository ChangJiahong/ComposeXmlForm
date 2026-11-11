package cn.changjiahong.composexmlform.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import cn.changjiahong.composexmlform.engine.node.Attr
import cn.changjiahong.composexmlform.engine.node.AttrDef
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.CoerceException
import cn.changjiahong.composexmlform.engine.value.FloatAdapter
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.TypeAdapter
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.display
import cn.changjiahong.composexmlform.engine.value.kindOf

/**
 * 尺寸语义：`fill` / `wrap` / 具体数值。
 *
 * 在**解析期**就定型，渲染器不用再判字符串。这消掉了旧实现里一个安静的坑：
 * `"fill".toFloatOrNull() ?: 0.dp` 会把 `fill` 静默变成 0dp。
 *
 * XML 里只写 `12`，不写 `12dp` —— 单位由属性语义决定，`Fixed` 存数值。
 */
sealed interface Dimension {

    data object Fill : Dimension

    data object Wrap : Dimension

    data class Fixed(val value: Float) : Dimension
}

/** `width="fill"` / `width="wrap"` / `width="120"`。 */
object DimensionAdapter : TypeAdapter<Dimension> {

    override val slotKind: SlotKind = SlotKind.Data

    override val typeName: String = "尺寸"

    override fun literal(raw: String, site: cn.changjiahong.composexmlform.engine.value.AttrSite): Value =
        when (val text = raw.trim()) {
            "fill" -> Dimension.Fill
            "wrap" -> Dimension.Wrap
            else -> Dimension.Fixed(
                text.toFloatOrNull()
                    ?: throw CoerceException("『$text』不是尺寸，只能是 fill / wrap 或数字", site),
            )
        }

    override fun coerce(value: Value, site: cn.changjiahong.composexmlform.engine.value.AttrSite): Dimension =
        when (value) {
            is Dimension -> value
            is Long -> Dimension.Fixed(value.toFloat())
            is Double -> Dimension.Fixed(value.toFloat())
            is String -> literal(value, site) as Dimension
            is Absent -> throw CoerceException("字段不存在，期望${typeName}", site)
            null -> throw CoerceException("值为 null，期望${typeName}", site)
            else -> throw CoerceException("期望${typeName}，收到 ${kindOf(value).display}", site)
        }

    override fun default(): Dimension? = null
}

/** 六个样式属性的声明。只在这一个文件里出现属性名。 */
internal object StyleAttrs {

    val width: AttrDef<Dimension> = Attr.of("width", DimensionAdapter)

    val height: AttrDef<Dimension> = Attr.of("height", DimensionAdapter)

    val padding: AttrDef<Float> = Attr.of("padding", FloatAdapter)

    val paddingHorizontal: AttrDef<Float> = Attr.of("paddingHorizontal", FloatAdapter)

    val paddingVertical: AttrDef<Float> = Attr.of("paddingVertical", FloatAdapter)

    val backgroundColor: AttrDef<String> = Attr.string("backgroundColor")

    val All: List<AttrDef<*>> = listOf(
        width,
        height,
        padding,
        paddingHorizontal,
        paddingVertical,
        backgroundColor,
    )
}

/**
 * 颜色字面量 → [Color]；不认识 → `null`。
 *
 * 换算留在渲染层而不是做成 `AttrType<Color>`：数据里只能是字符串，
 * 而 `"#FF5722"` / `"red"` / `"{{theme.primary}}"` 三种写法走同一条路。
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
 * 其余（含空串）回退 [Color.Unspecified]。
 *
 * 非法值不回退成报错：样式没生效的后果是肉眼可见的，为它加一套校验器等于
 * 把刚删掉的东西换个地方加回来。
 */
fun String?.toColor(): Color {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return Color.Unspecified
    return parseColorLiteral(raw) ?: Color.Unspecified
}

/** `normal` / `medium` / `semibold` / `bold`，或 100~900；不认识 → `null`（用主题默认）。 */
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
