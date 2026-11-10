package cn.changjiahong.composexmlform.engine.eval

import androidx.compose.runtime.mutableStateOf
import cn.changjiahong.composexmlform.engine.diag.EvalError
import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.env.LocalFrame
import cn.changjiahong.composexmlform.engine.env.Slot
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.normalize

/**
 * 一次函数调用的局部变量层：`name → Slot`。
 *
 * 与 env 链的 `EnvStore` 是**两个东西**（第 5 轮）：
 *
 * ```
 * Env 链        ← 界面结构（Foreach 行变量、组件局部量），跨调用存活
 * CallFrame    ← 一次函数调用，返回即丢
 * ```
 *
 * 为什么不合并：合并后递归调用会互相覆盖参数，而 `@{ x -> f(x) }` 里调 `f` 是常态。
 * 为什么 `val` 声明落在这里而不是 env（P-1）：脚本的局部量不该污染界面作用域，
 * 也不该出现在 `Foreach` 的行环境里。
 *
 * **[returned] 是「返回寄存器」而不是异常。** 用异常做非局部返回要么每次返回都构造一次栈，
 * 要么得关掉栈信息；这里用一个字段 + 循环里的判断，代价是一次字段读。
 */
class CallFrame internal constructor() : LocalFrame {

    private val slots = LinkedHashMap<String, Slot>()

    /** 已执行 `return`。外层语句循环看到它就停。 */
    internal var returned: Boolean = false
        private set

    internal var returnValue: Value = null
        private set

    override fun lookup(name: String): Slot? = slots[name]

    /**
     * 建局部绑定。**不发变更事件**（E-5）—— 建变量不是改数据。
     *
     * 同层重复声明直接报错：`val x = 1; val x = 2` 在 Kotlin 里是编译错误，
     * 这里只能在运行期拦（解析器不做符号表），但报错时机是「声明时」而不是「用到时」。
     */
    internal fun declare(name: String, value: Value, mutable: Boolean, span: Span? = null): Slot {
        if (slots.containsKey(name)) {
            throw EvalError("『$name』已经在本层定义过，不能重复声明", span)
        }
        val slot = Slot(mutableStateOf(normalize(value)), origin = null, mutable = mutable)
        slots[name] = slot
        return slot
    }

    internal fun signalReturn(value: Value) {
        returned = true
        returnValue = value
    }

    internal fun names(): List<String> = slots.keys.toList()

    override fun toString(): String = "CallFrame(${slots.keys.joinToString()})"
}
