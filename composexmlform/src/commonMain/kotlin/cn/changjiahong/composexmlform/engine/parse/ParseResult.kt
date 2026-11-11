package cn.changjiahong.composexmlform.engine.parse

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.diag.hasErrors
import cn.changjiahong.composexmlform.engine.env.EnvSchema
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.node.NodeTypes

/**
 * 解析结果：树 + 全部诊断。
 *
 * **诊断收集而非 fail-fast**（D-4）：一个标签上写错三个属性、另一个标签拼错名字，
 * 一次全部报出来。用户改一遍就能全改对，不用「改一个、跑一次、再发现一个」。
 *
 * 唯一的例外是结构解析（阶段 ①）：XML 本身没闭合时没有「继续解析」这回事，
 * 那时 [root] 必然是 `null`。
 *
 * [root] 可能非空但 [hasErrors] 为真 —— 例如某个子标签未注册。这种情况下**树是残缺的**，
 * 宿主应当用 [hasErrors] 决定要不要渲染，而不是用 `root != null`。
 */
class ParseResult(
    val root: Node?,
    val diagnostics: List<Diagnostic> = emptyList(),
) {

    val hasErrors: Boolean get() = diagnostics.hasErrors()

    val errors: List<Diagnostic> get() = diagnostics.filter { it.isError }

    val warnings: List<Diagnostic> get() = diagnostics.filter { !it.isError }

    /** 全部诊断拼成多行文本；传 [source] 时附上出错的源码片段。 */
    fun format(source: String? = null): String = diagnostics.joinToString("\n") { it.format(source) }

    override fun toString(): String =
        "ParseResult(root=${root?.let { "有" } ?: "无"}, ${diagnostics.size} 条诊断)"
}

/**
 * 解析一份 XML。
 *
 * [schema] 决定「哪些名字是全局对象」以及「哪些可写」——它必须在解析前就绪（E-3）。
 * [types] 是标签表；未注册的标签会被报错并跳过该子树。
 * [mode] 控制脚本能力档位（P-7），默认 [ScriptMode.ExpressionsOnly]。
 */
internal fun parse(
    xml: String,
    schema: EnvSchema,
    types: NodeTypes,
    mode: ScriptMode = ScriptMode.ExpressionsOnly,
): ParseResult {
    val diagnostics = mutableListOf<Diagnostic>()

    val scanned = XmlScanner(xml).scan()
    if (scanned is XmlScanResult.Err) {
        diagnostics += scanned.diagnostic
        return ParseResult(null, diagnostics)
    }

    val root = NodeParser(schema, types, mode, diagnostics).build((scanned as XmlScanResult.Ok).root)

    // 树构造完了但解析期一条错误都没有之外的兜底：根为 null 却没诊断，
    // 说明某个分支吞了错误。宁可报一条含糊的，也不要静默返回空树。
    if (root == null && !diagnostics.hasErrors()) {
        diagnostics += Diagnostic(
            Severity.Error,
            Code.XmlMalformed,
            "解析没有产出根节点，也没有记录任何错误",
        )
    }

    return ParseResult(root, diagnostics)
}
