package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 一次求值的现场。**所有入口都只带这一个参数**，不再往下传 env / frame / 扩展链。
 *
 * 四个字段各管一件事，缺一个就得在求值器里到处传：
 *
 * | 字段 | 作用 | 缺了会怎样 |
 * | --- | --- | --- |
 * | [env] | 作用域链 + 全局对象表 | 读不到 `data` |
 * | [frame] | 本次调用的 `val` / `var` | 局部变量落到 `data` 里 |
 * | [hint] | 裸名字兜底到哪个全局对象（V-13） | `onClick=@{ click() }` 找不到 `action.click` |
 * | [methods] | 宿主扩展链（F-1） | 宿主注册的方法永远查不到 |
 *
 * [hint] 为什么必须在这里：槽位期望类型是**声明期**信息，求值器看不到 `AttrDef`。
 * 由 `AttrSlot.read` 把 `def.type.slotKind` 塞进来，求值器就只需要「按提示兜底」，
 * 不需要知道当前在哪个属性上。
 *
 * [methods] 跟着 ctx 走而不是做成全局单例：全局单例会让同一个进程里的两个 runtime
 * 互相看到对方注册的方法，测试之间还会串用例（F-1）。
 */
class EvalContext(
    val env: Env,
    val frame: CallFrame? = null,
    val args: List<Value> = emptyList(),
    val hint: SlotKind = SlotKind.Data,
    val methods: MethodExtensions = MethodExtensions.Empty,
) {

    fun withFrame(frame: CallFrame?): EvalContext = EvalContext(env, frame, args, hint, methods)

    /** 构造脚本函数体的现场：换 frame 与 args，继承 env / hint / methods（S-1：不捕获局部）。 */
    internal fun forCall(frame: CallFrame, args: List<Value>): EvalContext =
        EvalContext(env, frame, args, hint, methods)

    override fun toString(): String = "EvalContext($env, frame=$frame, hint=$hint)"
}
