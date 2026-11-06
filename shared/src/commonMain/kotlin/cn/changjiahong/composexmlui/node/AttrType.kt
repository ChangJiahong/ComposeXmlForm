package cn.changjiahong.composexmlui.node

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlui.store.ObservableData
import kotlin.reflect.KClass

/**
 * 属性类型：把「XML 字面量」和「绑定求值结果」都收敛成属性声明的 T。
 *
 * 转换规则**从 T 推导**，不再由调用方传入：`AttrValue<Int>` 自动按整数解析，
 * `AttrValue<Boolean>` 自动按布尔解析。节点定义里因此看不到任何转换器。
 *
 * 两个入口的分工：
 * - [literal]：解析期把 XML 字符串转成 T。字面量永远是字符串，`"10"` 是 `"10"`，
 *   单位（`sp` / `dp`）由属性类型决定，不写在 XML 里。
 * - [adapt]：运行期把绑定求值结果收敛成 T。
 */
interface AttrType<T> {

    fun literal(raw: String, site: AttrSite): T

    fun adapt(value: Any?, site: AttrSite): T?
}

/**
 * 按声明类型取转换规则。
 *
 * `reified` 让 `T::class` 在调用点内联展开，KMP 下不需要反射；
 * 这也是「不传转换器」能成立的前提 —— 类型信息本来就在调用点。
 */
inline fun <reified T : Any> attrType(): AttrType<T> = AttrTypes.of(T::class)

object AttrTypes {

    /**
     * 内置类型表。查不到的类型走 [ObjectType]：只接受 `{{path}}` 绑定，并按 `T` 做运行期类型校验。
     *
     * 表里除基础类型外还有几个「框架值类型」（Dp / TextUnit / Color / FontWeight / TextAlign /
     * Dimension）：它们是属性的语义单位，不写进 XML，但要由属性类型决定怎么解释 `"14"`。
     * 这层内置是全局的，调用点仍然一个字都不多写。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> of(type: KClass<T>): AttrType<T> =
        BUILTINS[type] as AttrType<T>? ?: ObjectType(type)

    fun builtins(): Set<KClass<*>> = BUILTINS.keys
}

private val BUILTINS: Map<KClass<*>, AttrType<*>> = mapOf(
    String::class to StringType,
    Int::class to IntType,
    Long::class to LongType,
    Float::class to FloatType,
    Double::class to DoubleType,
    Boolean::class to BooleanType,
    Any::class to AnyType,
    Dp::class to DpType,
    TextUnit::class to TextUnitType,
    Color::class to ColorType,
    FontWeight::class to FontWeightType,
    TextAlign::class to TextAlignType,
    Dimension::class to DimensionType,
)

// ---------------------------------------------------------------------------
// 基础类型
// ---------------------------------------------------------------------------

private object StringType : AttrType<String> {
    override fun literal(raw: String, site: AttrSite): String = raw
    override fun adapt(value: Any?, site: AttrSite): String? =
        if (value == null) null else ObservableData.asString(value)
}

private object IntType : AttrType<Int> {
    override fun literal(raw: String, site: AttrSite): Int =
        raw.trim().toIntOrNull() ?: site.fail("属性值 '$raw' 无效，期望整数（例如 3）")

    override fun adapt(value: Any?, site: AttrSite): Int? = when (value) {
        null -> null
        is Int -> value
        is Number -> {
            val number = value.toDouble()
            if (number != number.toLong().toDouble()) site.fail("求值结果 $value 不是整数")
            value.toInt()
        }
        is String -> value.trim().toIntOrNull()
            ?: site.fail("求值结果 '$value' 无法转换为整数")

        else -> site.mismatch(value, "整数")
    }
}

private object LongType : AttrType<Long> {
    override fun literal(raw: String, site: AttrSite): Long =
        raw.trim().toLongOrNull() ?: site.fail("属性值 '$raw' 无效，期望长整数（例如 3）")

    override fun adapt(value: Any?, site: AttrSite): Long? = when (value) {
        null -> null
        is Long -> value
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: site.fail("求值结果 '$value' 无法转换为长整数")
        else -> site.mismatch(value, "长整数")
    }
}

private object FloatType : AttrType<Float> {
    override fun literal(raw: String, site: AttrSite): Float =
        raw.trim().toFloatOrNull() ?: site.fail("属性值 '$raw' 无效，期望数字")

    override fun adapt(value: Any?, site: AttrSite): Float? = when (value) {
        null -> null
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull() ?: site.fail("求值结果 '$value' 无法转换为数字")
        else -> site.mismatch(value, "数字")
    }
}

private object DoubleType : AttrType<Double> {
    override fun literal(raw: String, site: AttrSite): Double =
        raw.trim().toDoubleOrNull() ?: site.fail("属性值 '$raw' 无效，期望数字")

    override fun adapt(value: Any?, site: AttrSite): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: site.fail("求值结果 '$value' 无法转换为数字")
        else -> site.mismatch(value, "数字")
    }
}

private object BooleanType : AttrType<Boolean> {
    override fun literal(raw: String, site: AttrSite): Boolean = raw.toBooleanValue(site)

    override fun adapt(value: Any?, site: AttrSite): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.toBooleanValue(site)
        else -> site.mismatch(value, "布尔值")
    }
}

/**
 * 原样透传：给 `options`、`items`、`Select.value` 这类「数据本身」的属性用。
 *
 * 故意不 unwrap：`Foreach` / `Select` 需要拿到 `ObservableList` / `ObservableMap`
 * 才能继续订阅结构变化，转成普通集合会把响应式能力丢掉。
 */
private object AnyType : AttrType<Any> {
    override fun literal(raw: String, site: AttrSite): Any = raw
    override fun adapt(value: Any?, site: AttrSite): Any? = value
}

// ---------------------------------------------------------------------------
// 框架值类型：单位由属性决定，XML 里只写数字
// ---------------------------------------------------------------------------

/** 尺寸：只写数字，`12` → `12.dp`。 */
private object DpType : AttrType<Dp> {
    override fun literal(raw: String, site: AttrSite): Dp = raw.toDp(site)

    override fun adapt(value: Any?, site: AttrSite): Dp? = when (value) {
        null -> null
        is Dp -> value
        is Number -> value.toFloat().dp
        is String -> value.toDp(site)
        else -> site.mismatch(value, "dp 数值")
    }
}

/** 字号：只写数字，`14` → `14.sp`。 */
private object TextUnitType : AttrType<TextUnit> {
    override fun literal(raw: String, site: AttrSite): TextUnit = raw.toTextUnit(site)

    override fun adapt(value: Any?, site: AttrSite): TextUnit? = when (value) {
        null -> null
        is TextUnit -> value
        is Number -> value.toFloat().sp
        is String -> value.toTextUnit(site)
        else -> site.mismatch(value, "sp 数值")
    }
}

/** 颜色：`#RGB` / `#RRGGBB` / `#AARRGGBB`，或内置色名。 */
private object ColorType : AttrType<Color> {
    override fun literal(raw: String, site: AttrSite): Color = raw.toColor(site)

    override fun adapt(value: Any?, site: AttrSite): Color? = when (value) {
        null -> null
        is Color -> value
        is String -> value.toColor(site)
        else -> site.mismatch(value, "颜色（#RRGGBB 或色名）")
    }
}

/** 字重：`normal` / `medium` / `semibold` / `bold`，或 100~900。 */
private object FontWeightType : AttrType<FontWeight> {
    override fun literal(raw: String, site: AttrSite): FontWeight = raw.toFontWeight(site)

    override fun adapt(value: Any?, site: AttrSite): FontWeight? = when (value) {
        null -> null
        is FontWeight -> value
        is Number -> value.toInt().toFontWeightValue()
        is String -> value.toFontWeight(site)
        else -> site.mismatch(value, "字重")
    }
}

/** 对齐：`start` / `center` / `end`。 */
private object TextAlignType : AttrType<TextAlign> {
    override fun literal(raw: String, site: AttrSite): TextAlign = raw.toTextAlign(site)

    override fun adapt(value: Any?, site: AttrSite): TextAlign? = when (value) {
        null -> null
        is TextAlign -> value
        is String -> value.toTextAlign(site)
        else -> site.mismatch(value, "对齐方式（start / center / end）")
    }
}

/**
 * 尺寸语义：`fill` / `match` → [Dimension.Fill]，`wrap` / `auto` → [Dimension.Wrap]，
 * 其余按数字解析成 [Dimension.Fixed]。不认识的值直接报错，避免「写错静默变成 0dp」。
 */
private object DimensionType : AttrType<Dimension> {
    override fun literal(raw: String, site: AttrSite): Dimension = raw.toDimension(site)

    override fun adapt(value: Any?, site: AttrSite): Dimension? = when (value) {
        null -> null
        is Dimension -> value
        is Number -> Dimension.Fixed(value.toFloat().dp)
        is String -> value.toDimension(site)
        else -> site.mismatch(value, "尺寸（fill / wrap / 数值）")
    }
}

// ---------------------------------------------------------------------------
// 对象类型：没有字面量规则，只接受 {{path}} 绑定
// ---------------------------------------------------------------------------

/**
 * 业务对象类型。
 *
 * 字面量永远是字符串，而字符串不是 `User`，所以 `value="张三"` 一定失败 ——
 * 报错直接说「无法转换为 User」，不需要再解释「该属性只支持绑定表达式」：
 * 用户看到的本来就是类型不对。
 */
private class ObjectType<T : Any>(private val type: KClass<T>) : AttrType<T> {

    override fun literal(raw: String, site: AttrSite): T = site.fail(
        "属性值 '$raw' 无法转换为 ${type.simpleName}，该属性需要一个 ${type.simpleName} 对象，" +
            "请用 {{path}} 绑定",
    )

    @Suppress("UNCHECKED_CAST")
    override fun adapt(value: Any?, site: AttrSite): T? = when {
        value == null -> null
        type.isInstance(value) -> value as T
        else -> site.fail("求值得到 ${value::class.simpleName}，该属性需要 ${type.simpleName} 对象")
    }
}

// ---------------------------------------------------------------------------
// 解析工具：失败一律带 site，保证错误里有标签 / 属性 / 行号
// ---------------------------------------------------------------------------

private fun AttrSite.mismatch(value: Any, expected: String): Nothing =
    fail("求值得到 ${value::class.simpleName}，该属性需要 $expected")

private fun String.toBooleanValue(site: AttrSite): Boolean = when (trim().lowercase()) {
    "true", "yes", "1" -> true
    "false", "no", "0" -> false
    else -> site.fail("属性值 '$this' 无效，期望 true 或 false")
}

/** 只接受数字：单位由属性类型决定，写 `12` 而不是 `12dp`。 */
private fun String.toDp(site: AttrSite): Dp {
    val value = trim().toFloatOrNull()
        ?: site.fail("属性值 '$this' 无效，期望数字（单位由属性决定，写 12 而不是 12dp）")
    return value.dp
}

/** 只接受数字：单位由属性类型决定，写 `14` 而不是 `14sp`。 */
private fun String.toTextUnit(site: AttrSite): TextUnit {
    val value = trim().toFloatOrNull()
        ?: site.fail("属性值 '$this' 无效，期望数字（单位由属性决定，写 14 而不是 14sp）")
    return value.sp
}

private fun String.toColor(site: AttrSite): Color {
    val raw = trim()
    if (raw.startsWith("#")) {
        val hex = raw.substring(1)
        val argb = when (hex.length) {
            3 -> "FF${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
            6 -> "FF$hex"
            8 -> hex
            else -> site.fail("颜色 '$raw' 无效，期望 #RGB、#RRGGBB 或 #AARRGGBB")
        }
        val value = argb.lowercase().toLongOrNull(16)
            ?: site.fail("颜色 '$raw' 无效，含非十六进制字符")
        return Color(value)
    }
    return NAMED_COLORS[raw.lowercase()]
        ?: site.fail(
            "颜色 '$raw' 无效，期望十六进制颜色（如 #FF5722）或内置色名 ${NAMED_COLORS.keys.sorted()}",
        )
}

private fun String.toFontWeight(site: AttrSite): FontWeight = when (trim().lowercase()) {
    "normal", "regular" -> FontWeight.Normal
    "medium" -> FontWeight.Medium
    "semibold", "semi_bold" -> FontWeight.SemiBold
    "bold" -> FontWeight.Bold
    else -> {
        val number = trim().toIntOrNull()
            ?: site.fail("字重 '$this' 无效，期望 normal / medium / semibold / bold 或 100~900")
        number.toFontWeightValue()
    }
}

private fun Int.toFontWeightValue(): FontWeight =
    if (this in 1..9) FontWeight(this * 100) else FontWeight(this)

private fun String.toTextAlign(site: AttrSite): TextAlign = when (trim().lowercase()) {
    "start", "left" -> TextAlign.Start
    "center" -> TextAlign.Center
    "end", "right" -> TextAlign.End
    else -> site.fail("对齐方式 '$this' 无效，期望 start / center / end")
}

private fun String.toDimension(site: AttrSite): Dimension = when (trim().lowercase()) {
    "fill", "fillmax", "match", "match_parent" -> Dimension.Fill
    "wrap", "wrap_content", "auto" -> Dimension.Wrap
    else -> Dimension.Fixed(toDp(site))
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
