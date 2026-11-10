package cn.changjiahong.composexmlform.engine.value

/**
 * 引擎里流动的一切都是这个类型。
 *
 * **不引入 `sealed class Value`** —— 渲染器与宿主最终要的是原始 `String` / `Float` / `Boolean`，
 * 封闭类型会让每次读取多一层 `when` 解包，而 KMP 上没有逃逸分析保证能把它优化掉。
 * 规范形态由**写入边界的一次 [normalize]** 保证，之后内部不再判断形态。
 *
 * 规范形态（只有这 10 种，别的类型出现即说明有人绕过了 normalize）：
 *
 * ```
 * Absent | null | Boolean | Long | Double | String | DataObject | DataArray | HostObject | Function
 * ```
 *
 * 分组依据只有一条：**值语义**（标量，传参即复制）还是**引用语义**（其余，传参共享同一对象）。
 */
typealias Value = Any?

/**
 * 位置不存在。
 *
 * 与 `null` 的区别是表单场景的刚需：`{}` 与 `{a: null}` 在读 `a` 时前者是 [Absent]、后者是 `null`。
 * 脏检查（「字段没下发」）与必填校验（「字段为空」）都依赖这个区分（V-3）。
 */
object Absent {
    override fun toString(): String = "Absent"
}

/** 宿主业务对象。只能整体读写 / 传参 / 引用比较，**不可按路径下钻**（V-2）。 */
class HostObject(val raw: Any) {
    override fun toString(): String = "HostObject(${raw::class.simpleName ?: "?"})"
}

/**
 * 函数是一等值（第 5 轮决定），也是 `ValAttr<Function>` 槽位的类型。
 *
 * [target] 是「调用方的对象值」，对应 Lua 的 `:` 与 Python 的 bound method：
 * ```
 * data.user.list.append(x)
 * └──────┬──────┘└──┬──┘└┬┘
 *      target      fn  args
 * ```
 * 参数不携带签名（X-2），个数与含义由槽位语义决定。
 */
fun interface Function {
    fun invoke(target: Value, args: List<Value>): Value
}

/**
 * 需要挂起上下文的函数（宿主动作、`http.get` 之类）。
 *
 * [invoke] 一定抛：属性在 Composable 里求值，**不能 suspend**。谁能调它由 `eval` 判断，
 * 这里只负责把误用变成明确错误而不是死锁。
 */
interface SuspendFunction : Function {
    suspend fun invokeSuspend(target: Value, args: List<Value>): Value

    override fun invoke(target: Value, args: List<Value>): Value =
        throw IllegalStateException("该函数需要挂起上下文，不能出现在属性表达式中")
}

/**
 * 值类型分类，只用于**方法查找**分派（`list.append` 找 `Array` 表）与宿主扩展注册（F-1）。
 *
 * 不做成「值自己的属性」：分类是对值的解释，不是值的一部分。
 */
enum class ValueKind {
    Object,
    Array,
    String,
    Number,
    Boolean,
    Function,
    Host,

    /** `null` / [Absent]，没有方法可查。 */
    None,
}

fun kindOf(value: Value): ValueKind = when (value) {
    is DataObject -> ValueKind.Object
    is DataArray -> ValueKind.Array
    is String -> ValueKind.String
    is Long, is Double -> ValueKind.Number
    is Boolean -> ValueKind.Boolean
    is Function -> ValueKind.Function
    is HostObject -> ValueKind.Host
    else -> ValueKind.None
}

/** 显示用名字，错误消息里写「`append` 不是 Long 的方法」而不是「不是 kotlin.Long 的方法」。 */
val ValueKind.display: String
    get() = when (this) {
        ValueKind.Object -> "对象"
        ValueKind.Array -> "数组"
        ValueKind.String -> "字符串"
        ValueKind.Number -> "数字"
        ValueKind.Boolean -> "布尔"
        ValueKind.Function -> "函数"
        ValueKind.Host -> "宿主对象"
        ValueKind.None -> "空值"
    }
