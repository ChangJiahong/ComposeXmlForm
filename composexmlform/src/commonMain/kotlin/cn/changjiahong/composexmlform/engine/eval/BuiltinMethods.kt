package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.EvalError
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Fn
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.ValueKind

/**
 * 内置类型方法表：`data.list.append(x)` 里的 `append` 从这儿来。
 *
 * **不可变全局常量**（V-11）：表本身没有可变状态，所以不需要每个 runtime 一份。
 * 需要「按 runtime 定制」的是 [MethodExtensions]，两者分开正是为了不让定制污染常量。
 *
 * ### 查找顺序（F-2）
 *
 * ```
 * 数据字段 → 内置方法 → 扩展链 → Absent
 * ```
 *
 * 数据字段优先是必须的：`data.obj.keys` 若真有 `keys` 字段，用户要的是那个字段。
 * 这也解释了为什么全局函数里也有 `keys(obj)` / `has(obj, k)` —— 当字段遮蔽了方法时，
 * 它们是逃生舱而不是重复。
 *
 * ### 为什么方法要写成 `Function` 而不是容器内部的特殊分支
 *
 * 方法是一等值：`val f = data.list.append` 之后 `f(1)` 照样能跑（target 由调用方给）。
 * 塞进容器内部的特判做不到这一点，还会让「读成员」在不同容器上有不同语义。
 */
object BuiltinMethods {

    private val table: Map<ValueKind, Map<String, Function>> = mapOf(
        ValueKind.Array to arrayMethods(),
        ValueKind.String to stringMethods(),
        ValueKind.Object to objectMethods(),
    )

    /**
     * 全部内置方法名。宿主注册扩展时用它做冲突检查（F-3）：
     * 与内置同名会让「到底调了谁」取决于查找顺序，那是查不出来的 bug。
     */
    val names: Set<String> = table.values.flatMapTo(LinkedHashSet()) { it.keys }

    fun lookup(kind: ValueKind, name: String): Function? = table[kind]?.get(name)

    /** 注册期检查：这个名字在该类型上是不是已被内置占用。 */
    fun conflicts(kind: ValueKind, name: String): Boolean = table[kind]?.containsKey(name) == true
}

// ── 数组 ──────────────────────────────────────────────────────

private fun arrayMethods(): Map<String, Function> = mapOf(
    // append 返回数组本身，方便 `list.append(1).append(2)` 这种链式写法
    "append" to Fn.of { target, args -> asArray(target, "append").append(args.oneArg("append")) },

    "insert" to Fn.of { target, args ->
        val index = args.argAt("insert", 0).asIndex("insert")
        val value = args.argAt("insert", 1)
        asArray(target, "insert").insert(index, value)
    },

    "remove" to Fn.of { target, args ->
        asArray(target, "remove").removeAt(args.oneArg("remove").asIndex("remove"))
    },

    "indexOf" to Fn.of { target, args ->
        asArray(target, "indexOf").indexOf(args.oneArg("indexOf")).toLong()
    },

    "size" to Fn.of { target, args ->
        args.noArgs("size")
        asArray(target, "size").size.toLong()
    },
)

// ── 字符串 ────────────────────────────────────────────────────

private fun stringMethods(): Map<String, Function> = mapOf(
    "upper" to Fn.of { target, args -> args.noArgs("upper"); asText(target, "upper").uppercase() },
    "lower" to Fn.of { target, args -> args.noArgs("lower"); asText(target, "lower").lowercase() },
    "trim" to Fn.of { target, args -> args.noArgs("trim"); asText(target, "trim").trim() },
    "length" to Fn.of { target, args -> args.noArgs("length"); asText(target, "length").length.toLong() },

    "split" to Fn.of { target, args ->
        val separator = showValue(args.oneArg("split"))
        if (separator.isEmpty()) throw EvalError("『split』的分隔符不能为空")
        DataArray.ofValues(asText(target, "split").split(separator))
    },

    "contains" to Fn.of { target, args ->
        asText(target, "contains").contains(showValue(args.oneArg("contains")))
    },
)

// ── 对象 ──────────────────────────────────────────────────────

private fun objectMethods(): Map<String, Function> = mapOf(
    "keys" to Fn.of { target, args ->
        args.noArgs("keys")
        DataArray.ofValues(asObject(target, "keys").keys())
    },

    "has" to Fn.of { target, args ->
        asObject(target, "has").member(showValue(args.oneArg("has"))) !== Absent
    },

    "remove" to Fn.of { target, args ->
        asObject(target, "remove").removeMember(showValue(args.oneArg("remove")))
    },
)

// ── 接收者检查 ────────────────────────────────────────────────
// 方法是一等值（可以被 `val f = list.append` 取出来），所以 target 的类型在运行期不一定对得上。

private fun asArray(target: Value, method: String): DataArray =
    target as? DataArray ?: throw typeError("『$method』只能用于数组", target)

private fun asText(target: Value, method: String): String =
    target as? String ?: throw typeError("『$method』只能用于字符串", target)

private fun asObject(target: Value, method: String): DataObject =
    target as? DataObject ?: throw typeError("『$method』只能用于对象", target)
