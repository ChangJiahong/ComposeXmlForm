package cn.changjiahong.composexmlform.parser

import cn.changjiahong.composexmlform.binding.BindingExpression
import cn.changjiahong.composexmlform.binding.BindingParser
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.core.JsonCodec
import cn.changjiahong.composexmlform.core.Severity
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.core.toDiagnostic
import cn.changjiahong.composexmlform.node.ActionUsage
import cn.changjiahong.composexmlform.node.AttrEntry
import cn.changjiahong.composexmlform.node.AttrSite
import cn.changjiahong.composexmlform.node.AttrSource
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.BranchNode
import cn.changjiahong.composexmlform.node.FieldRules
import cn.changjiahong.composexmlform.node.IfNode
import cn.changjiahong.composexmlform.node.LayoutNode
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.UnknownNode
import cn.changjiahong.composexmlform.node.ValidationPlan
import cn.changjiahong.composexmlform.renderer.NodeRenderer
import kotlin.reflect.KClass

/**
 * 引擎注册表：解析与渲染**分别持有两张表**。
 *
 * - [nodes]：标签 → 怎么造节点。只需要校验时，注册这一张就够。
 * - [renderers]：节点类型 → 怎么画。可以整体替换内置渲染，也可以只为子类新增。
 *
 * 两者由注册动词（`leaf` / `container`）一起写入，避免「注册了节点忘了注册渲染器」；
 * 需要单独控制时直接操作 [nodes] / [renderers]。
 */
class EngineRegistry(
    val nodes: NodeRegistry = NodeRegistry(),
    val renderers: RendererRegistry = RendererRegistry(),
) {

    /**
     * 引擎本身支持的协议版本。
     *
     * XML 根元素可以写 `minEngine="2"` 要求更高版本；高于本值时出 [Code.LayoutTooNew] 诊断，
     * 而不是让用户看到一堆「未注册的组件标签」——那是版本不兼容，不是 bug。
     */
    val engineVersion: Int = ENGINE_VERSION

    /**
     * 注册表的「代数」：两张子表各自的版本号之和，任何注册 / 摘除都会让它变化。
     *
     * 用途很具体：`DynamicLayout` 用它做 `remember` 的 key。没有它的话，
     * 界面组合完再 `registry.leaf(...)` 不会重建运行时，`<X>` 照旧报「未注册」——
     * 可变容器被当成不可变标识，这是之前 `LayoutCache` 犯过的同一类错误。
     *
     * 放在子表而不是这里，是为了让**直接操作 `registry.nodes.register(...)`**
     * 这条底层逃生舱也被覆盖到。
     */
    val version: Int get() = nodes.version + renderers.version

    // ---------------- 五个动词 ----------------
    //
    // 各只有一个重载：节点类型 T 由构造引用推断，KClass 由 reified 取 —— 不再是
    // 「`TextNode::class` 与 `::TextNode` 写两遍、写错也能编译」。
    //
    // `NodeRenderer` 是 fun interface（R6），所以渲染逻辑既可以是实例也可以是尾 lambda：
    // ```
    // leaf("Text", ::TextNode, TextNodeRenderer())
    // leaf("Chip", ::ChipNode) { node -> Chip(node.label.get("")) }
    // ```

    /** 新增一个叶子组件。标签已存在时抛错 —— 覆盖内置是显式动作，走 [replace]。 */
    inline fun <reified T : Node> leaf(
        tag: String,
        noinline create: (Attrs) -> T,
        renderer: NodeRenderer<T>,
    ) = registerLeaf(tag, T::class, create, renderer, requireExisting = false)

    /** 新增一个容器组件。语义同 [leaf]，只是工厂多接一个 `children`。 */
    inline fun <reified T : LayoutNode> container(
        tag: String,
        noinline create: (Attrs, List<Node>) -> T,
        renderer: NodeRenderer<T>,
    ) = registerContainer(tag, T::class, create, renderer, requireExisting = false)

    /**
     * 覆盖一个已存在的标签。
     *
     * 要求标签已存在，是为了抓住反向的手误：本想覆盖内置，却把标签名打错了 ——
     * 那种情况用 [leaf] 会「成功」注册出一个永远没人用的组件。
     */
    inline fun <reified T : Node> replace(
        tag: String,
        noinline create: (Attrs) -> T,
        renderer: NodeRenderer<T>,
    ) = registerLeaf(tag, T::class, create, renderer, requireExisting = true)

    inline fun <reified T : LayoutNode> replace(
        tag: String,
        noinline create: (Attrs, List<Node>) -> T,
        renderer: NodeRenderer<T>,
    ) = registerContainer(tag, T::class, create, renderer, requireExisting = true)

    /** 只换画法，标签不动。 */
    inline fun <reified T : Node> repaint(renderer: NodeRenderer<T>) =
        renderers.register(T::class, renderer)

    @PublishedApi
    internal fun <T : Node> registerLeaf(
        tag: String,
        type: KClass<T>,
        create: (Attrs) -> T,
        renderer: NodeRenderer<T>,
        requireExisting: Boolean,
    ) {
        checkTagState(tag, requireExisting, leaf = true)
        nodes.register(tag, create)
        renderers.register(type, renderer)
    }

    @PublishedApi
    internal fun <T : Node> registerContainer(
        tag: String,
        type: KClass<T>,
        create: (Attrs, List<Node>) -> T,
        renderer: NodeRenderer<T>,
        requireExisting: Boolean,
    ) {
        checkTagState(tag, requireExisting, leaf = false)
        nodes.registerLayout(tag, create)
        renderers.register(type, renderer)
    }

    private fun checkTagState(tag: String, requireExisting: Boolean, leaf: Boolean) {
        val exists = nodes.has(tag)
        if (requireExisting && !exists) {
            throw XmlUiException(
                "标签 <$tag> 尚未注册，replace 只能覆盖已存在的组件。新增请用 ${verb(leaf)}(...)",
                tag = tag,
                code = Code.UnknownTag,
            )
        }
        if (!requireExisting && exists) {
            throw XmlUiException(
                "标签 <$tag> 已注册。覆盖请用 replace(\"$tag\", ...)",
                tag = tag,
                code = Code.Structure,
            )
        }
    }

    private fun verb(leaf: Boolean): String = if (leaf) "leaf" else "container"

    /** 摘掉一个标签。渲染器不随之删除：可能还有其它标签复用同一个节点类型。 */
    fun unregister(tag: String) = nodes.unregister(tag)

    fun hasTag(tag: String): Boolean = nodes.has(tag)

    fun tags(): Set<String> = nodes.tags()

    /**
     * 解析 XML 并构建节点树。
     *
     * 纯函数：`(xml, config, mode) → ParseResult`，不缓存、不持有状态。
     *
     * - `config` 是**服务器下发的只读布局资源**（下拉字典、枚举文案、字段规则）。
     *   它在解析期被就地求值成常量，运行时不存在 —— 所以 `{{config.x}}` 只能出现在
     *   只读槽位，写回会在解析期报错。
     * - 传入的 `config` 会被深拷贝归一化，之后改调用方自己的 Map 不影响已解析的界面。
     *
     * @throws XmlUiException `mode = Strict` 且存在 Error 级诊断时；异常的 `diagnostics`
     *   带着完整列表，不需要重新解析一遍才能拿到剩下几条。
     */
    fun parse(
        xml: String,
        config: Map<String, Any?> = emptyMap(),
        mode: ParseMode = ParseMode.Strict,
    ): ParseResult {
        val context = ParseContext(JsonCodec.normalizeMap(config), mode)
        val document = XmlParser.parse(xml)
        val root = buildNode(stripProtocolAttributes(document, context), parent = null, context = context)
        // Tolerant 把「有问题」交给调用方看 diagnostics，自己绝不抛：它的用途就是「坏了也要能渲染」
        if (mode == ParseMode.Strict && context.hasError) throw context.toException()
        return ParseResult(
            root = root,
            actionUsages = context.actionUsages.toList(),
            diagnostics = context.diagnostics.toList(),
            validationPlan = ValidationPlan(context.fieldRules.toList()),
        )
    }

    /**
     * 建节点：属性先整体解析成 [Attrs]，再交给节点工厂。
     *
     * 顺序是有讲究的：
     * 1. 分支标签（`Then` / `Else`）不是组件，直接造成 [BranchNode]；
     * 2. 先把所有属性的绑定语法解析一遍 —— 语法错误优先于「属性不被支持」报出；
     * 3. 容器先建 children 再建节点，叶子先建节点（子元素会在建完后被拒绝）；
     * 4. 工厂跑完再校验白名单。
     *
     * 任何一步失败都**不向上抛**：记一条诊断，返回 [UnknownNode] 占位，让兄弟节点继续建。
     * `Strict` 模式在收集完之后统一抛（见 [parse]），`Tolerant` 模式只留 Warning。
     */
    internal fun buildNode(element: XmlElement, parent: XmlElement?, context: ParseContext): Node = try {
        buildNodeOrThrow(element, parent, context)
    } catch (e: XmlUiException) {
        context.record(e)
        UnknownNode(element.tag, element.line)
    }

    private fun buildNodeOrThrow(element: XmlElement, parent: XmlElement?, context: ParseContext): Node {
        val branchOwner = BRANCH_OWNERS[element.tag]
        if (branchOwner != null) {
            if (parent?.tag != branchOwner) {
                throw XmlUiException(
                    "<${element.tag}> 只能出现在 <$branchOwner> 内",
                    tag = element.tag,
                    line = element.line,
                    code = Code.Structure,
                )
            }
            return BranchNode(element.tag, element.children.map { buildNode(it, element, context) }, element.line)
        }

        val attrs = attrsOf(element, context)

        val node = if (nodes.isLayout(element.tag)) {
            nodes.createLayout(element.tag, attrs, element.children.map { buildNode(it, element, context) })
        } else {
            val leaf = nodes.createLeaf(element.tag, attrs)
            if (element.children.isNotEmpty()) {
                throw XmlUiException(
                    "不允许包含子节点，实际包含 <${element.children.first().tag}>",
                    tag = element.tag,
                    line = element.line,
                    code = Code.Structure,
                )
            }
            leaf
        }

        attrs.assertAllConsumed(context::record)
        return node
    }

    /** 属性名 → 未定型条目。类型要等节点声明才知道，所以这里只解析来源。 */
    private fun attrsOf(element: XmlElement, context: ParseContext): Attrs {
        val entries = LinkedHashMap<String, AttrEntry>(element.attributes.size)
        element.attributes.forEach { (name, raw) ->
            val site = AttrSite(element.tag, name, element.line)
            val source = try {
                when (val expression = BindingParser.parseAttribute(raw)) {
                    is BindingExpression.Literal -> AttrSource.Const(expression.raw)
                    is BindingExpression.Path -> AttrSource.Var(expression.path)
                }
            } catch (e: XmlUiException) {
                throw XmlUiException(
                    e.reason,
                    tag = element.tag,
                    attribute = name,
                    line = element.line,
                    code = Code.TypeMismatch,
                )
            }
            entries[name] = AttrEntry(raw, source, site)
        }
        return Attrs(
            attributeMap = entries,
            tag = element.tag,
            line = element.line,
            config = context.config,
            actionUsages = context.actionUsages,
            fieldRules = context.fieldRules,
        )
    }

    private companion object {

        /**
         * 协议版本。改动 XML 语法 / 属性语义时 +1，
         * 这样服务器端的 `minEngine` 才有判定依据。
         */
        const val ENGINE_VERSION = 1

        /**
         * 分支标签 → 宿主组件。分支是 XML 语法的一部分，不是可注册的组件，
         * 所以它留在解析器里；扩展新的分支型组件时在这里加一行即可。
         */
        val BRANCH_OWNERS = mapOf(
            IfNode.THEN to IfNode.TAG,
            IfNode.ELSE to IfNode.TAG,
        )

        /** 协议属性必须从根元素上摘走，否则会被 `assertAllConsumed` 当成未知属性。 */
        val PROTOCOL_ATTRIBUTES = setOf("version", "minEngine")
    }

    /**
     * 摘掉根元素上的协议属性，并检查 `minEngine`。
     *
     * 只在根上生效：`<Column version="2">` 这种写法在子节点上仍是未知属性。
     */
    private fun stripProtocolAttributes(root: XmlElement, context: ParseContext): XmlElement {
        val protocol = root.attributes.filterKeys { it in PROTOCOL_ATTRIBUTES }
        if (protocol.isEmpty()) return root

        protocol["minEngine"]?.let { raw ->
            val required = raw.trim().toIntOrNull()
            if (required == null) {
                context.recordDiagnostic(
                    Diagnostic(
                        severity = Severity.Error,
                        code = Code.TypeMismatch,
                        message = "minEngine 必须是整数，实际为 '$raw'",
                        tag = root.tag,
                        attribute = "minEngine",
                        line = root.line,
                    ),
                )
            } else if (required > engineVersion) {
                context.recordDiagnostic(
                    Diagnostic(
                        severity = Severity.Error,
                        code = Code.LayoutTooNew,
                        message = "布局要求引擎版本 >= $required，当前引擎是 $engineVersion。" +
                            "请升级客户端，或让服务端下发兼容版本。",
                        tag = root.tag,
                        attribute = "minEngine",
                        line = root.line,
                    ),
                )
            }
        }

        return XmlElement(
            tag = root.tag,
            attributes = root.attributes.filterKeys { it !in PROTOCOL_ATTRIBUTES },
            children = root.children,
            line = root.line,
            column = root.column,
        )
    }
}

/**
 * 一次解析的全部可变状态：配置、模式、已收集的诊断、动作引用。
 *
 * 显式传递而不是挂在 `Attrs` 上：诊断收集是**解析器**的职责，
 * 属性包只负责「按类型取值」这一件事。
 */
internal class ParseContext(
    val config: Map<String, Any?>,
    val mode: ParseMode,
) {

    private val collected = mutableListOf<Diagnostic>()
    val actionUsages = mutableListOf<ActionUsage>()
    val fieldRules = mutableListOf<FieldRules>()

    val diagnostics: List<Diagnostic> get() = collected
    val hasError: Boolean get() = collected.any { it.severity == Severity.Error }

    fun record(error: XmlUiException) {
        collected += error.toDiagnostic(severityOf(error))
    }

    fun recordDiagnostic(diagnostic: Diagnostic) {
        collected += diagnostic
    }

    /**
     * 只有「标签不认识」与「属性不认识」两类在 Tolerant 下降级：
     * 它们都是「新版本客户端才有 / 打错字」，忽略掉界面还能用。
     * 类型不匹配、结构错误、动作未注册仍是 Error —— 它们意味着行为已经不对了。
     */
    private fun severityOf(error: XmlUiException): Severity {
        val downgradable = error.code == Code.UnknownTag || error.code == Code.UnknownAttribute
        return if (mode == ParseMode.Tolerant && downgradable) Severity.Warning else Severity.Error
    }

    /** 聚合成一个异常：消息只列前 5 条，完整列表放 `diagnostics`。 */
    fun toException(): XmlUiException {
        val errors = collected.filter { it.severity == Severity.Error }
        if (errors.isEmpty()) {
            return XmlUiException("解析失败", diagnostics = collected.toList())
        }
        val head = errors.take(5).joinToString("\n") { "  · " + it.format() }
        val more = if (errors.size > 5) "\n  …还有 ${errors.size - 5} 条" else ""
        val first = errors.first()
        return XmlUiException(
            reason = "解析失败，共 ${errors.size} 处问题：\n$head$more",
            tag = first.tag,
            attribute = first.attribute,
            line = first.line,
            code = first.code,
            diagnostics = collected.toList(),
        )
    }
}
