package cn.changjiahong.composexmlform.engine.value

import cn.changjiahong.composexmlform.engine.diag.EngineException
import cn.changjiahong.composexmlform.engine.diag.Span

/**
 * 槽位种类。
 *
 * Kotlin 泛型运行期擦除，拿不到 `T`，所以「这个槽位是 Function 吗」必须由 `AttrType` 在
 * **声明处**给出常量（A-2）。只在声明处判断，不涉及反射。
 */
enum class SlotKind {
    Data,
    Function,
}

/** 属性求值现场：标签 / 属性名 / 源码区间。错误消息靠它定位，缺了就只能报「第 0 行」。 */
data class AttrSite(
    val tag: String?,
    val attribute: String,
    val span: Span? = null,
)

/** 收敛失败。**不是** `EvalError`：它发生在「值已经有了，只是类型不对」，处置是降级而非中断。 */
class CoerceException(
    message: String,
    val site: AttrSite,
) : EngineException(message, site.span)

/**
 * `Value → T` 的收敛规则，**全系统唯一一处**类型转换（第 3 轮决定 3）。
 *
 * 删掉了 `StoreOwner.readInt/readFloat/readString…` 与 `ObservableData.asString/asBoolean` 的原因：
 * 同一个值有三处解释，必然出现「同一份数据两处读法不同」。
 */
interface TypeAdapter<T> {

    /** 声明期常量，见 [SlotKind]。 */
    val slotKind: SlotKind

    /** 错误消息里的类型名（「期望数字，收到字符串」）。 */
    val typeName: String

    /**
     * 字面量如何解释。`"3"` 对数字适配器是 `3L`，对字符串适配器是 `"3"`。
     *
     * 结果仍要过一遍 [coerce]：收敛点只有一处，字面量不例外。
     */
    fun literal(raw: String, site: AttrSite): Value

    /** `Value → T`。失败抛 [CoerceException]，由调用方决定降级还是上报。 */
    fun coerce(value: Value, site: AttrSite): T

    /** 类型自身的「空值」。`String` 是 `""`，数字是 `null`（没有有意义的空数字）。 */
    fun default(): T?
}

/**
 * 数字转显示文本：整数值不显示 `.0`。
 *
 * 这是**显示**规则不是计算规则 —— `{{data.count}}` 里 `count` 是 `3.0` 时应该显示 `3`，
 * 但 `data.count / 2` 仍然是 `1.5`（P-6）。
 */
internal fun formatDouble(value: Double): String =
    if (value % 1.0 == 0.0 && value >= -9.0e15 && value <= 9.0e15) {
        value.toLong().toString()
    } else {
        value.toString()
    }

// ── 内置适配器 ────────────────────────────────────────────────

object StringAdapter : TypeAdapter<String> {

    override val slotKind = SlotKind.Data
    override val typeName = "字符串"

    override fun literal(raw: String, site: AttrSite): Value = raw

    override fun coerce(value: Value, site: AttrSite): String = when (value) {
        is String -> value
        is Long -> value.toString()
        is Double -> formatDouble(value)
        is Boolean -> value.toString()
        is Absent -> throw CoerceException("字段不存在，期望${typeName}", site)
        null -> throw CoerceException("值为 null，期望${typeName}", site)
        else -> throw CoerceException("期望${typeName}，收到 ${kindOf(value).display}", site)
    }

    override fun default(): String = ""
}

object LongAdapter : TypeAdapter<Long> {

    override val slotKind = SlotKind.Data
    override val typeName = "整数"

    override fun literal(raw: String, site: AttrSite): Value =
        raw.trim().toLongOrNull() ?: throw CoerceException("『${raw.trim()}』不是整数", site)

    override fun coerce(value: Value, site: AttrSite): Long = when (value) {
        is Long -> value
        is Double ->
            if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
                value.toLong()
            } else {
                throw CoerceException("$value 不是整数", site)
            }

        is String ->
            value.trim().toLongOrNull() ?: throw CoerceException("『$value』不是整数", site)

        is Absent -> throw CoerceException("字段不存在，期望${typeName}", site)
        null -> throw CoerceException("值为 null，期望${typeName}", site)
        else -> throw CoerceException("期望${typeName}，收到 ${kindOf(value).display}", site)
    }

    override fun default(): Long? = null
}

object IntAdapter : TypeAdapter<Int> {

    override val slotKind = SlotKind.Data
    override val typeName = "整数"

    override fun literal(raw: String, site: AttrSite): Value = LongAdapter.literal(raw, site)

    override fun coerce(value: Value, site: AttrSite): Int {
        val long = LongAdapter.coerce(value, site)
        if (long < Int.MIN_VALUE || long > Int.MAX_VALUE) {
            throw CoerceException("$long 超出 Int 范围", site)
        }
        return long.toInt()
    }

    override fun default(): Int? = null
}

object DoubleAdapter : TypeAdapter<Double> {

    override val slotKind = SlotKind.Data
    override val typeName = "数字"

    override fun literal(raw: String, site: AttrSite): Value =
        raw.trim().toDoubleOrNull() ?: throw CoerceException("『${raw.trim()}』不是数字", site)

    override fun coerce(value: Value, site: AttrSite): Double = when (value) {
        is Double -> value
        is Long -> value.toDouble()
        is String ->
            value.trim().toDoubleOrNull() ?: throw CoerceException("『$value』不是数字", site)

        is Absent -> throw CoerceException("字段不存在，期望${typeName}", site)
        null -> throw CoerceException("值为 null，期望${typeName}", site)
        else -> throw CoerceException("期望${typeName}，收到 ${kindOf(value).display}", site)
    }

    override fun default(): Double? = null
}

/** `dp` / `sp` / 透明度这类需要 `Float` 的槽位。 */
object FloatAdapter : TypeAdapter<Float> {

    override val slotKind = SlotKind.Data
    override val typeName = "数字"

    override fun literal(raw: String, site: AttrSite): Value = DoubleAdapter.literal(raw, site)

    override fun coerce(value: Value, site: AttrSite): Float = DoubleAdapter.coerce(value, site).toFloat()

    override fun default(): Float? = null
}

/**
 * 布尔。
 *
 * **只接受 `Boolean`**，不做 truthy（P-3）：`if (data.list)` 在 truthy 语言里判的是「非空」，
 * 而写的人往往以为是「不为 null」。强制 Boolean 让这类误用当场报错。
 */
object BooleanAdapter : TypeAdapter<Boolean> {

    override val slotKind = SlotKind.Data
    override val typeName = "布尔值"

    override fun literal(raw: String, site: AttrSite): Value = when (raw.trim()) {
        "true" -> true
        "false" -> false
        else -> throw CoerceException("『${raw.trim()}』不是布尔值，只能是 true / false", site)
    }

    override fun coerce(value: Value, site: AttrSite): Boolean = when (value) {
        is Boolean -> value
        is Absent -> throw CoerceException("字段不存在，期望${typeName}", site)
        null -> throw CoerceException("值为 null，期望${typeName}", site)
        else -> throw CoerceException("期望${typeName}，收到 ${kindOf(value).display}", site)
    }

    override fun default(): Boolean? = false
}

/**
 * 函数槽位（`onClick` 这类）。
 *
 * `literal` 一定抛：Function 槽位只接受 `@{…}`（第 11 轮矩阵）。把字面量 `"login"`
 * 当成函数名是上一版 `ActionRef` 的做法，已被否掉 —— 它让「字符串」同时有两种含义。
 */
object FunctionAdapter : TypeAdapter<Function> {

    override val slotKind = SlotKind.Function
    override val typeName = "函数"

    override fun literal(raw: String, site: AttrSite): Value =
        throw CoerceException("函数槽位只能写 @{…}，不能写字面量『$raw』", site)

    override fun coerce(value: Value, site: AttrSite): Function = when (value) {
        is Function -> value
        is Absent -> throw CoerceException("函数不存在（未注册？）", site)
        null -> throw CoerceException("函数为 null", site)
        else -> throw CoerceException("期望${typeName}，收到 ${kindOf(value).display}", site)
    }

    override fun default(): Function? = null
}

/** `Value` 原样透传的槽位：自定义组件声明 `Attr.any("payload")` 时用。 */
object AnyAdapter : TypeAdapter<Value> {

    override val slotKind = SlotKind.Data
    override val typeName = "任意值"

    override fun literal(raw: String, site: AttrSite): Value = raw

    override fun coerce(value: Value, site: AttrSite): Value = value

    override fun default(): Value = null
}
