package cn.changjiahong.composexmlform.engine.node

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.value.AttrSite

/** 一条待消费的原始属性：定型后的写法 + 属性名在原文里的区间。 */
internal class AttrEntry(val ref: AttrRef, val span: Span)

/**
 * 一个标签上的全部属性，**按声明逐个消费**（A-3）。
 *
 * 用法是构造函数的参数：
 *
 * ```kotlin
 * NodeType("Text", attributes = listOf(Attr.string("text"), Attr.bool("bold"))) { set, _ ->
 *     TextNode(set.attr(Attr.string("text")).let { ... })
 * }
 * ```
 *
 * `attr()` 里做三件事，全都**不需要 schema**：
 * 1. 取写法（没写就是 `Absent`）；
 * 2. 若必填缺失 → `MissingRequiredAttribute`；
 * 3. 若写法与槽位能力不匹配 → 对应错误码（`PLAN.md` 的九格矩阵）。
 *
 * 第 4 件事「`{{config.x}}` 绑到了只读对象」需要全局对象能力表，留给解析层（S6）——
 * 本包不 import `env`，否则节点树就绑死在具体环境上。
 *
 * 诊断是**收集**而不是抛（D-4）：一个标签上写错三个属性，一次全报出来。
 */
class AttrSet internal constructor(
    val tag: String,
    val span: Span,
    private val entries: Map<String, AttrEntry>,
    private val declared: List<AttrDef<*>>,
    private val diagnostics: MutableList<Diagnostic>,
) {

    /** 已经被 `attr()` / `raw()` 取过的名字。`finish()` 靠它找漏网属性。 */
    private val consumed = HashSet<String>(entries.size)

    /**
     * 取一条属性。
     *
     * 恒返回非空：缺失时返回 `present == false` 的槽位，读取走默认值。
     * 返回 `null` 会逼每个调用点写 `?:`，而「缺了用默认」本来就是绝大多数情况。
     */
    fun <T> attr(def: AttrDef<T>): AttrSlot<T> {
        consumed += def.name
        val entry = entries[def.name]
        val ref = entry?.ref ?: AttrRef.Absent
        val site = AttrSite(tag, def.name, entry?.span ?: span)

        if (entry == null) {
            if (def.required) {
                diagnostics += Diagnostic(
                    Severity.Error,
                    Code.MissingRequiredAttribute,
                    "『$tag』缺少必填属性『${def.name}』",
                    site.span,
                    tag,
                    def.name,
                    "需要${def.type.typeName}",
                )
            }
        } else {
            misplaced(def, ref)?.let { (code, message) ->
                diagnostics += Diagnostic(Severity.Error, code, message, site.span, tag, def.name)
            }
        }

        return AttrSlot(def, ref, site)
    }

    /**
     * 取未声明属性的原文（自定义组件的逃生舱）。
     *
     * 只对字面量有效：`{{}}` / `{}` / `@{}` 返回 `null`，因为它们的「原文」是脚本片段，
     * 交给宿主解析等于让宿主再实现一遍引擎。
     */
    fun raw(name: String): String? {
        consumed += name
        return (entries[name]?.ref as? AttrRef.Literal)?.raw
    }

    /**
     * 构造结束时收尾：仍有未声明也未消费的属性 → `UnknownAttribute`。
     *
     * 已声明但没被 `attr()` 读的**不报**：声明了却没用到，是节点实现自己的事，
     * 报成「未知属性」会误导（属性明明是它自己声明的）。
     */
    fun finish() {
        if (entries.isEmpty()) return
        val known = declared.mapTo(HashSet(declared.size)) { it.name }

        for ((name, entry) in entries) {
            if (name in consumed || name in known) continue
            val available = if (known.isEmpty()) "" else "；可用属性：${known.joinToString("、")}"
            diagnostics += Diagnostic(
                Severity.Error,
                Code.UnknownAttribute,
                "『$tag』没有属性『$name』$available",
                entry.span,
                tag,
                name,
                nearest(name, known),
            )
        }
    }

    /**
     * 写法 × 槽位矩阵（第 11 轮定稿）。
     *
     * | 槽位 | `"raw"` | `{{path}}` | `{expr}` | `@{…}` |
     * | --- | --- | --- | --- | --- |
     * | `ValAttr<T>` | 合法 | 合法 | 合法 | 报错 |
     * | `VarAttr<T>` | 报错 | **唯一** | 报错 | 报错 |
     * | `ValAttr<Function>` | 报错 | 报错 | 报错 | **唯一** |
     *
     * 注意 `VarAttr` 收 `{{}}` 在这一层只是**形态**合法；路径是否指向可写对象由 S6 判（X-4）。
     */
    private fun misplaced(def: AttrDef<*>, ref: AttrRef): Pair<Code, String>? {
        val name = def.name
        return when {
            // 可写性先判：`{…}` / `@{…}` 落到可写槽位，问题是「不可写」而不是「不是函数」
            def.writable -> when (ref) {
                is AttrRef.Literal -> Code.LiteralInWritableSlot to
                    "『$name』是可写槽位，不能写字面量${ref.describe()}；要绑定请写 {{path}}"

                is AttrRef.Bind, AttrRef.Absent -> null

                else -> Code.ReadOnlySlot to
                    "『$name』是可写槽位，只接受 {{path}}；${ref.describe()} 是只读的"
            }

            def.isFunction -> when (ref) {
                AttrRef.Absent, is AttrRef.Lambda -> null
                else -> Code.InvalidAttributeValue to
                    "『$name』是函数槽位，只能写 @{…}，当前是${ref.describe()}"
            }

            ref is AttrRef.Lambda -> Code.InvalidAttributeValue to
                "『$name』不是函数槽位，不能写 @{…}；要计算请写成 {…}"

            else -> null
        }
    }
}

/**
 * 「你是不是想写 X」。只对距离 ≤ 2 的候选给建议 —— 差得远时提示纯属噪音。
 */
private fun nearest(name: String, candidates: Set<String>): String? {
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    for (candidate in candidates) {
        val distance = editDistance(name, candidate)
        if (distance < bestDistance) {
            bestDistance = distance
            best = candidate
        }
    }
    return if (best != null && bestDistance <= 2) "你是不是想写『$best』" else null
}

/**
 * Levenshtein 距离。属性名就几十个字符，全矩阵在这里是常数级开销，
 * 用不着上前缀树或 BK 树。
 */
private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)

    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + cost,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
