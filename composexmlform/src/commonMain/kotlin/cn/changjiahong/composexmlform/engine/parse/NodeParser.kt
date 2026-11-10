package cn.changjiahong.composexmlform.engine.parse

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.EngineException
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.env.EnvSchema
import cn.changjiahong.composexmlform.engine.env.GlobalKind
import cn.changjiahong.composexmlform.engine.node.AttrDef
import cn.changjiahong.composexmlform.engine.node.AttrEntry
import cn.changjiahong.composexmlform.engine.node.AttrRef
import cn.changjiahong.composexmlform.engine.node.AttrSet
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.node.NodeIds
import cn.changjiahong.composexmlform.engine.node.NodeType
import cn.changjiahong.composexmlform.engine.node.NodeTypes
import cn.changjiahong.composexmlform.engine.node.describe
import cn.changjiahong.composexmlform.engine.value.CoerceException

/**
 * 阶段 ②③④ 的驱动：把 [XmlNode] 树变成 `Node` 树。
 *
 * 四个阶段在同一趟递归里按固定顺序执行，顺序本身是设计的一部分：
 *
 * ```
 * 定型属性（③，不需要作用域）
 *   → 收集本元素的结构属性（②，`item="row"`）
 *     → 解析本元素的绑定（④，用**不含自己**的作用域）
 *       → 压栈，解析 children（②→③→④ 递归）
 *         → 弹栈，构造节点
 * ```
 *
 * 为什么「收集」排在「解析本元素绑定」之前、但「压栈」排在之后：
 * `<Foreach item="row" source="{{row.x}}">` 里的 `row` 不该指自己引入的那个 ——
 * 那会解析成一个永远没有值的自引用。作用域从 **children** 才开始生效。
 *
 * 为什么「解析绑定」不能延后到压栈之后：同理，本元素的属性看到的必须是外层作用域。
 */
internal class NodeParser(
    private val schema: EnvSchema,
    private val types: NodeTypes,
    private val mode: ScriptMode,
    private val diagnostics: MutableList<Diagnostic>,
) {

    private val scope = ScopeStack(schema)
    private val typer = AttrTyper(diagnostics, mode)

    fun build(root: XmlNode): Node? = element(root, NodeIds.Root)

    private fun element(xml: XmlNode, id: String): Node? {
        val type = types[xml.tag]
        if (type == null) {
            reportUnknownTag(xml)
            return null
        }

        // ③ 属性定型：raw → AttrRef。语法错的属性留一个「空壳」占位，
        // 避免 AttrSet 再把它当成「没写」而重复报一次必填缺失
        val entries = LinkedHashMap<String, AttrEntry>(xml.attributes.size)
        for (attr in xml.attributes) {
            val ref = typer.type(attr, xml.tag)
            entries[attr.name] = AttrEntry(ref ?: AttrRef.Absent, attr.nameSpan)
        }

        // ② 结构属性 → 本元素要引入的名字（只收裸标识符，N-2）
        val introduced = structuralNames(type, entries, xml)

        // ④ 命名空间与可写性：用当前作用域（不含自己刚收集的）
        resolveBindings(type, entries, xml)

        // ④ 子节点结构
        type.childRule.check(xml.children.map { it.tag })?.let { violation ->
            diagnostics += Diagnostic(
                Severity.Error,
                violation.code,
                violation.message,
                xml.tagSpan,
                xml.tag,
            )
        }

        scope.push(introduced)
        val children = try {
            xml.children.mapIndexedNotNull { index, child ->
                element(child, NodeIds.child(id, index))
            }
        } finally {
            scope.pop()
        }

        val set = AttrSet(xml.tag, xml.tagSpan, entries, type.attributes, diagnostics)
        val node = construct(type, id, set, children, xml)
        set.finish()
        return node
    }

    // ── ② 作用域 ─────────────────────────────────────────────

    private fun structuralNames(
        type: NodeType,
        entries: Map<String, AttrEntry>,
        xml: XmlNode,
    ): List<String> {
        val defs = type.attributes.filter { it.structural }
        if (defs.isEmpty()) return emptyList()

        val names = ArrayList<String>(defs.size)
        for (def in defs) {
            val entry = entries[def.name] ?: continue
            val ref = entry.ref
            if (ref is AttrRef.Absent) continue

            if (ref !is AttrRef.Literal) {
                report(
                    Code.InvalidAttributeValue,
                    "结构属性『${def.name}』只收裸标识符（例如 item=\"row\"），不能写 ${ref.describe()}",
                    entry.span,
                    xml,
                    def.name,
                )
                continue
            }

            val name = ref.raw.trim()
            when {
                !isIdentifier(name) -> report(
                    Code.InvalidAttributeValue,
                    "结构属性『${def.name}』必须是合法标识符，实际是『$name』",
                    entry.span,
                    xml,
                    def.name,
                )

                // 全局对象名是保留字，不能被局部变量遮蔽（E-6）
                schema.isReserved(name) -> report(
                    Code.InvalidAttributeValue,
                    "『$name』是全局对象名（保留字），不能作为局部变量名",
                    entry.span,
                    xml,
                    def.name,
                )

                name in names -> report(
                    Code.InvalidAttributeValue,
                    "这个标签用两个结构属性引入了同一个名字『$name』",
                    entry.span,
                    xml,
                    def.name,
                )

                else -> names += name
            }
        }
        return names
    }

    // ── ④ 命名空间与可写性 ───────────────────────────────────

    private fun resolveBindings(
        type: NodeType,
        entries: MutableMap<String, AttrEntry>,
        xml: XmlNode,
    ) {
        for (name in entries.keys.toList()) {
            val def = type.defOf(name) ?: continue
            val entry = entries[name] ?: continue
            val ref = entry.ref
            if (ref !is AttrRef.Bind) continue
            entries[name] = AttrEntry(resolveBind(ref, def, entry.span, xml), entry.span)
        }
    }

    /**
     * 首段定级：**绝对**（首段是全局对象名）/ **局部**（在作用域栈里）/ **兜底**（其余）。
     *
     * 兜底是刻意的：`{{name}}` 是「`data.name` 的语法糖」（第 6 轮定的），
     * 若无条件要求首段必须是全局对象，这个语法糖就没了。
     *
     * 代价是拼错的全局对象名（`{{dta.user}}`）静态判不出来 —— 用编辑距离补：
     * 首段离某个全局对象名只差一两个字符时，高置信度地报 `UnknownGlobal`；
     * 差得远就当 `data` 的字段，交给运行期（E-1 的原话）。
     */
    private fun resolveBind(
        bind: AttrRef.Bind,
        def: AttrDef<*>,
        span: Span,
        xml: XmlNode,
    ): AttrRef {
        val path = bind.path
        val head = path.head ?: return bind

        val kind = schema.kindOf(head)
        if (kind != null) {
            if (def.writable && kind != GlobalKind.Writable) {
                report(
                    Code.NotWritableNamespace,
                    "『$head』不可写，不能作为可写槽位『${def.name}』的绑定目标",
                    span,
                    xml,
                    def.name,
                )
            }
            // 打上绝对标记：运行期跳过整条作用域链，这是快路径
            return AttrRef.Bind(path.withRoot(head))
        }

        if (scope.isLocal(head)) return bind

        val nearestGlobal = scope.nearestGlobal(head)
        if (nearestGlobal != null) {
            report(
                Code.UnknownGlobal,
                "『$head』不是已注册的全局对象，也不会是本层的局部变量",
                span,
                xml,
                def.name,
                hint = "你是不是想写『$nearestGlobal』",
            )
        }
        return bind
    }

    // ── ⑤ 构造 ───────────────────────────────────────────────

    private fun construct(
        type: NodeType,
        id: String,
        set: AttrSet,
        children: List<Node>,
        xml: XmlNode,
    ): Node? = try {
        type.create(id, set, children)
    } catch (error: CoerceException) {
        diagnostics += Diagnostic(
            Severity.Error,
            Code.InvalidAttributeValue,
            error.message ?: "属性值不合法",
            error.site.span ?: xml.tagSpan,
            xml.tag,
            error.site.attribute,
        )
        null
    } catch (error: EngineException) {
        diagnostics += Diagnostic(
            Severity.Error,
            Code.ScriptRuntime,
            error.message ?: "构造节点失败",
            error.span ?: xml.tagSpan,
            xml.tag,
        )
        null
    } catch (error: Throwable) {
        // 宿主注册的 create 有 bug。不吞异常会让一次拼错属性炸掉整个编译，
        // 而它本来只是一条可上报的诊断。
        diagnostics += Diagnostic(
            Severity.Error,
            Code.NodeConstruction,
            "构造 <${xml.tag}> 时抛异常：${error.message ?: error::class.simpleName}",
            xml.tagSpan,
            xml.tag,
        )
        null
    }

    // ── 诊断 ─────────────────────────────────────────────────

    private fun reportUnknownTag(xml: XmlNode) {
        val near = nearest(xml.tag, types.tags)
        val hint = when {
            near != null -> "你是不是想写 <$near>"
            types.tags.isEmpty() -> "还没有注册任何节点类型"
            else -> "已注册：${types.tags.take(8).joinToString("、")}"
        }
        diagnostics += Diagnostic(
            Severity.Error,
            Code.UnknownTag,
            "未注册的标签 <${xml.tag}>",
            xml.tagSpan,
            xml.tag,
            hint = hint,
        )
    }

    private fun report(
        code: Code,
        message: String,
        span: Span,
        xml: XmlNode,
        attribute: String,
        hint: String? = null,
    ) {
        diagnostics += Diagnostic(Severity.Error, code, message, span, xml.tag, attribute, hint)
    }
}

private fun isIdentifier(text: String): Boolean {
    if (text.isEmpty()) return false
    val first = text[0]
    if (!first.isLetter() && first != '_') return false
    return text.all { it.isLetterOrDigit() || it == '_' }
}
