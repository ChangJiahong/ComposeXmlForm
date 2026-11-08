package cn.changjiahong.composexmlform.parser

import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.core.Severity
import cn.changjiahong.composexmlform.node.ActionUsage
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.ValidationPlan

/**
 * 解析结果：节点树 + 解析期收集到的旁证。
 *
 * 为什么不是直接返回 [Node]：有两类信息只能随树一起带出来，事后遍历树拿不到
 * （或者代价大得多）：
 * - [actionUsages]：解析期收集的动作引用。`If` / `Foreach` 各分支里的引用也在其中，
 *   而且带真实标签 / 属性名 / 行号 —— 遍历树只能看到 `ButtonNode`。
 * - [diagnostics]：解析期发现但不至于中断的问题（`Tolerant` 模式下全是 Warning）。
 * - [validationPlan]：字段级校验规则（R11）。规则挂在绑定路径上，运行时遍历树也拿不到。
 */
class ParseResult(
    val root: Node,
    val actionUsages: List<ActionUsage> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val validationPlan: ValidationPlan = ValidationPlan.Empty,
) {

    /** 是否存在 Error 级诊断（`Tolerant` 下据此判断「这份布局是不是有问题」）。 */
    val hasError: Boolean get() = diagnostics.any { it.severity == Severity.Error }

    val warnings: List<Diagnostic> get() = diagnostics.filter { it.severity == Severity.Warning }
    val errors: List<Diagnostic> get() = diagnostics.filter { it.severity == Severity.Error }
}

/**
 * 解析模式。
 *
 * - [Strict]（默认）：收集完全部诊断后，只要有 Error 就抛 `XmlUiException`（异常带完整列表）。
 *   「一次报一个错、修完再报下一个」对服务器下发的布局是最糟的体验。
 * - [Tolerant]：未知标签降级成占位框、未知属性忽略，其余照常渲染。
 */
enum class ParseMode {
    Strict,
    Tolerant,
}
