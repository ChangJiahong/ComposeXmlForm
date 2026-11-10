package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.EvalError
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.HostObject
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.display
import cn.changjiahong.composexmlform.engine.value.formatDouble
import cn.changjiahong.composexmlform.engine.value.kindOf

/**
 * 参数个数与类型的运行期校验。
 *
 * 为什么不在注册期校验：`Function` 不携带签名（X-2），XML 里也没有类型标注，个数只有在**调用时**
 * 才知道。补偿手段就是把消息写清楚 —— 用户唯一能自查的就是「我传了几个」。
 */
internal fun List<Value>.noArgs(method: String) {
    if (isNotEmpty()) throw EvalError("『$method』不接受参数，收到 $size 个")
}

internal fun List<Value>.oneArg(method: String): Value {
    if (size != 1) throw EvalError("『$method』期望 1 个参数，收到 $size 个")
    return this[0]
}

internal fun List<Value>.twoArgs(method: String): Pair<Value, Value> {
    if (size != 2) throw EvalError("『$method』期望 2 个参数，收到 $size 个")
    return this[0] to this[1]
}

internal fun List<Value>.argAt(method: String, index: Int): Value {
    if (size <= index) {
        throw EvalError("『$method』期望 ${index + 1} 个参数，收到 $size 个")
    }
    return this[index]
}

/** 下标参数：只接受整数值。字符串 `"2"` 也收 —— 从 `{{index}}` 来的下标常常是字符串。 */
internal fun Value.asIndex(method: String): Int {
    val number = when (this) {
        is Long -> this
        is Double -> if (this % 1.0 == 0.0) toLong() else throw notAnIndex(method)
        is String -> trim().toLongOrNull() ?: throw notAnIndex(method)
        else -> throw notAnIndex(method)
    }
    if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) throw notAnIndex(method)
    return number.toInt()
}

private fun notAnIndex(method: String) = EvalError("『$method』的下标必须是整数")

internal fun isNumberValue(value: Value): Boolean = value is Long || value is Double

/**
 * 显示文本。`str()` / 字符串拼接 / 错误消息共用一套规则，避免「同一个值三种写法」。
 *
 * [Absent] 显示成空串而不是 `"Absent"`：这是给人看的文本，缺值在界面上就该是空白。
 */
fun showValue(value: Value): String = when (value) {
    null -> "null"
    Absent -> ""
    is String -> value
    is Long -> value.toString()
    is Double -> formatDouble(value)
    is Boolean -> value.toString()
    is HostObject -> value.raw.toString()
    is Function -> "<函数>"
    else -> value.toString()
}

internal fun typeError(what: String, value: Value): EvalError =
    EvalError("$what，收到${kindOf(value).display}")
