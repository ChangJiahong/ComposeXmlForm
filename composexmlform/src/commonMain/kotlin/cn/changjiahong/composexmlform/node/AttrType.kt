package cn.changjiahong.composexmlform.node

import cn.changjiahong.composexmlform.store.ObservableData
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
     * 表里**只有基础类型**加框架自己的 [Dimension]：Compose 的 `Dp` / `TextUnit` / `Color` /
     * `FontWeight` / `TextAlign` 不属于这一层（见包边界约定）。属性声明的类型因此也是
     * `Float` / `String`，渲染器再换算成 Compose 值（`renderer/ValueAdapters.kt`）。
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
    override fun literal(raw: String, site: AttrSite): Float = raw.toFloatValue(site)

    override fun adapt(value: Any?, site: AttrSite): Float? = when (value) {
        null -> null
        is Number -> value.toFloat()
        is String -> value.toFloatValue(site)
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

/**
 * 尺寸语义：`fill` / `match` → [Dimension.Fill]，`wrap` / `auto` → [Dimension.Wrap]，
 * 其余按数字解析成 [Dimension.Fixed]。不认识的值直接报错，避免「写错静默变成 0dp」。
 *
 * 只存 `Float`（dp 的数值），`Dp` 由渲染器加 —— 见包边界约定。
 */
private object DimensionType : AttrType<Dimension> {
    override fun literal(raw: String, site: AttrSite): Dimension = raw.toDimension(site)

    override fun adapt(value: Any?, site: AttrSite): Dimension? = when (value) {
        null -> null
        is Dimension -> value
        is Number -> Dimension.Fixed(value.toFloat())
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
private fun String.toNumber(site: AttrSite): Float = trim().toFloatOrNull()
    ?: site.fail("属性值 '$this' 无效，期望数字（单位由属性决定，写 12 而不是 12dp）")

/**
 * `Float` 属性的字面量解析：带上「不要写单位」的提示。
 *
 * 这句提示原来写在 `toDp` / `toTextUnit` 里，那两个类型删掉后由这里统一承担 ——
 * `padding` / `fontSize` / `spacing` 现在都是 `Float`，用户看到的错误应该还是同一句话。
 */
private fun String.toFloatValue(site: AttrSite): Float = trim().toFloatOrNull()
    ?: site.fail("属性值 '$this' 无效，期望数字（不要在值里写单位，如 14sp / 12dp，单位由属性决定）")

private fun String.toDimension(site: AttrSite): Dimension = when (trim().lowercase()) {
    "fill", "fillmax", "match", "match_parent" -> Dimension.Fill
    "wrap", "wrap_content", "auto" -> Dimension.Wrap
    else -> Dimension.Fixed(toNumber(site))
}
