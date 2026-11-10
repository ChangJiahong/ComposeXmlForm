package cn.changjiahong.composexmlform.engine.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.node.AttrSlot

/**
 * 渲染器读属性的一行写法：`node.text.value()`。
 *
 * 三件事一次做完，且顺序固定：
 * 1. 从 [LocalEnv] 与 [LocalMethods] 造求值现场；
 * 2. 求值（`renderValue`，失败降级 + `AttributeFallback`）；
 * 3. 收敛成 `T?`。
 *
 * 为什么不把这三个参数（ctx / sink）留给调用方传：每个渲染器都会传成同一个值，
 * 而**漏传一次就会静默失去降级能力** —— 那正是 `R-4` 想避免的情况。
 */
@Composable
fun <T> AttrSlot<T>.value(): T? = renderValue(rememberEvalContext(), LocalDiagnostics.current)

/** 带缺省值的读法：`node.enabled.orElse(true)`。 */
@Composable
fun <T> AttrSlot<T>.orElse(default: T): T = value() ?: default

/**
 * 枚举型属性（对齐 / 排列 / 字体粗细这类）的标准读法。
 *
 * 节点把值声明成 `String`，映射表留在渲染器：`Alignment` / `Arrangement` 属于 Compose 排版体系，
 * 不该出现在节点定义的类型里。不认识的值**当场报错**而不是静默用默认值 ——
 * `alignment="centar"` 静默变成 `Start` 是最难查的一类问题。
 */
@Composable
fun <T> AttrSlot<String>.choose(mapping: Map<String, T>, default: T): T {
    val raw = value()?.trim().orEmpty()
    if (raw.isEmpty()) return default
    mapping[raw]?.let { return it }
    mapping[raw.lowercase()]?.let { return it }

    // 不认识的值 → 降级成默认值 + 一条诊断。抛错会让整页白屏，而「对齐没生效」
    // 恰恰是那种不该拖垮界面的问题；但静默回退又最难查，所以必须报出来。
    val sink = LocalDiagnostics.current
    val diagnostic = Diagnostic(
        severity = Severity.Warning,
        code = Code.AttributeFallback,
        message = "『${site.attribute}』的值 '$raw' 无效，可用：" +
            mapping.keys.sorted().joinToString(" / "),
        span = site.span,
        tag = site.tag,
        attribute = site.attribute,
    )
    SideEffect { sink.report(diagnostic) }
    return default
}
