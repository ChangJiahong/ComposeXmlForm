package cn.changjiahong.composexmlform.engine.render

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.EngineException
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.eval.EvalContext
import cn.changjiahong.composexmlform.engine.eval.read
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.value.CoerceException

/**
 * 渲染层的属性读取：**求值失败不崩界面**（R-4）。
 *
 * `read` 抛错在脚本里合适 —— 脚本是宿主写的，出错该立刻知道；
 * 在渲染里不合适 —— 一条脏数据让整页白屏，代价完全不成比例。
 *
 * 降级规则：
 *
 * | 情况 | 处置 |
 * | --- | --- |
 * | 读到了 | 直接用 |
 * | 没写 | `def.defaultValue()`，不报诊断（这是正常配置） |
 * | 求值 / 收敛失败，且不是必填 | 落到 `def.defaultValue()` + 一条 `AttributeFallback` |
 * | 求值 / 收敛失败，且是必填 | **抛** —— 构造期就该拦住的契约被破坏了 |
 *
 * 「必填失败就抛」是唯一不降级的分支：`required` 属性的值缺失说明数据契约被破坏，
 * 继续渲染只会把错误埋进界面里。而解析期已经报过 `MissingRequiredAttribute`，
 * 走到这里说明是**运行期数据**不满足声明。
 *
 * 诊断是即时上报的：同一次组合里读同一个属性只报一次，但重组会重复报 ——
 * 去重由 [DiagnosticSink] 的实现负责（按 `code + tag + attribute`），引擎不做全局去重表
 * （那会变成一个跨 runtime 共享的可变状态）。
 */
fun <T> AttrSlot<T>.renderValue(
    ctx: EvalContext,
    sink: DiagnosticSink = DiagnosticSink.None,
): T? = try {
    read(ctx)
} catch (error: CoerceException) {
    degrade(error.message ?: "取值失败", error, sink)
} catch (error: EngineException) {
    degrade(error.message ?: "求值失败", error, sink)
}

private fun <T> AttrSlot<T>.degrade(
    reason: String,
    error: EngineException,
    sink: DiagnosticSink,
): T? {
    if (def.required) throw error

    sink.report(
        Diagnostic(
            severity = Severity.Warning,
            code = Code.AttributeFallback,
            message = "『${def.name}』取值失败：$reason；已改用默认值",
            span = site.span,
            tag = site.tag,
            attribute = site.attribute,
        ),
    )
    return def.defaultValue()
}
