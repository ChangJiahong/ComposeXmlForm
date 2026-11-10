package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.EvalError
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Fn
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.HostObject
import cn.changjiahong.composexmlform.engine.value.MutableDataObject
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.deepCopy
import cn.changjiahong.composexmlform.engine.value.valuesEqual
import kotlinx.serialization.json.JsonPrimitive

/**
 * 全局函数：不挂在任何全局对象下、直接按名字调用的那一组。
 *
 * 它们**不进 `globals` 表**（E-6：全局对象名是保留字，`{{keys}}` 应该读 `data.keys` 而不是函数），
 * 而是求值器在「env 链与兜底都没命中」之后才查。于是：
 *
 * ```
 * {keys(obj)}      → 函数
 * {{keys}}         → 绑定路径，读的是 data.keys
 * ```
 *
 * 同名冲突的处置：数据里真有 `keys` 字段时**字段优先**（它就是用户的数据），
 * 要调函数请换写法 —— 这正是 `keys(obj)` / `obj.keys()` / `obj.keys` 三种入口并存的原因。
 *
 * 表是不可变常量，不需要每个 runtime 一份。
 */
object BuiltinFunctions {

    val map: Map<String, Function> = mapOf(
        "new" to Fn.of { _, args -> construct(args) },
        "copy" to Fn.of1 { deepCopy(it) },
        "json" to Fn.of1 { toJson(it) },

        // 类型转换：str / num / int / bool 四个，各自只做一件事
        "str" to Fn.of1 { showValue(it) },
        "num" to Fn.of1 { asDouble(it, "num") },
        "int" to Fn.of1 { asLong(it, "int") },
        "bool" to Fn.of1 { asBoolean(it, "bool") },

        "equals" to Fn.of2 { a, b -> valuesEqual(a, b) },

        // 与 obj.keys() / list.size() 对应，用于「字段遮蔽了方法」时取长度/键名
        "length" to Fn.of1 { lengthOf(it) },
        "keys" to Fn.of1 { keysOf(it) },
        "has" to Fn.of2 { target, key -> hasKey(target, key) },
    )

    fun lookup(name: String): Function? = map[name]

    private fun construct(args: List<Value>): Value = when (args.size) {
        0 -> MutableDataObject()
        1 -> when (val proto = args[0]) {
            is DataObject, is DataArray -> deepCopy(proto)
            else -> throw typeError("new 的参数必须是对象或数组", proto)
        }

        else -> throw EvalError("『new』期望 0 或 1 个参数，收到 ${args.size} 个")
    }

    private fun lengthOf(value: Value): Long = when (value) {
        is String -> value.length.toLong()
        is DataArray -> value.size.toLong()
        is DataObject -> value.keys().size.toLong()
        Absent -> 0L
        else -> throw typeError("length 只接受字符串 / 数组 / 对象", value)
    }

    private fun keysOf(value: Value): Value = when (value) {
        is DataObject -> DataArray.ofValues(value.keys())
        is DataArray -> DataArray.ofValues(value.elements.indices.map { it.toString() })
        else -> throw typeError("keys 只接受对象或数组", value)
    }

    private fun hasKey(target: Value, key: Value): Boolean = when (target) {
        is DataObject -> target.member(showValue(key)) !== Absent
        is DataArray -> {
            val index = showValue(key).toIntOrNull()
            index != null && index in 0 until target.size
        }

        else -> throw typeError("has 的第一个参数必须是对象或数组", target)
    }

    private fun asDouble(value: Value, fn: String): Double = when (value) {
        is Double -> value
        is Long -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: throw EvalError("『$value』不是数字")
        else -> throw typeError("$fn 只接受数字或字符串", value)
    }

    private fun asLong(value: Value, fn: String): Long = when (value) {
        is Long -> value
        is Double ->
            if (value % 1.0 == 0.0) {
                value.toLong()
            } else {
                throw EvalError("$value 不是整数，要取整请先用别的办法处理")
            }

        is String -> value.trim().toLongOrNull() ?: throw EvalError("『$value』不是整数")
        else -> throw typeError("$fn 只接受数字或字符串", value)
    }

    private fun asBoolean(value: Value, fn: String): Boolean = when (value) {
        is Boolean -> value
        is String -> when (value.trim()) {
            "true" -> true
            "false" -> false
            else -> throw EvalError("『$value』不是布尔值，只能是 true / false")
        }

        else -> throw typeError("$fn 只接受布尔值或 true/false 字符串", value)
    }

    /**
     * 序列化成 JSON 文本。
     *
     * 手写而不是走 `JsonElement` 树：`Value` 本来就不是 `JsonElement`，转一道树只是多一次全量复制；
     * 字符串转义借 `JsonPrimitive` 保证与标准实现一致（转义规则自己写必错）。
     */
    private fun toJson(value: Value): String = when (value) {
        null, Absent, is Function, is HostObject -> "null"
        is Boolean, is Long -> value.toString()
        is Double -> if (value % 1.0 == 0.0 && value > -1e15 && value < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

        is String -> JsonPrimitive(value).toString()
        is DataObject -> value.keys().joinToString(",", "{", "}") { key ->
            JsonPrimitive(key).toString() + ":" + toJson(value.member(key))
        }

        is DataArray -> value.elements.joinToString(",", "[", "]") { toJson(it) }
        else -> "null"
    }
}
