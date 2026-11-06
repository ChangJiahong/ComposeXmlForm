package cn.changjiahong.composexmlui.parser

import cn.changjiahong.composexmlui.binding.ActionRef
import cn.changjiahong.composexmlui.binding.BindingExpression
import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.AttrSite
import cn.changjiahong.composexmlui.node.AttrSource
import cn.changjiahong.composexmlui.node.AttrType
import cn.changjiahong.composexmlui.node.AttrValue
import cn.changjiahong.composexmlui.node.BranchNode
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.node.attrType

/**
 * 节点构建上下文：**解析期唯一的能力入口**。它承担三件事，缺一不可：
 *
 * 1. 属性源：XML 的原始字符串。
 * 2. 转换与定位：把字符串收敛成属性声明的类型，失败时用「标签 + 属性 + 行号」报错。
 *    类型由调用点声明（`AttrValue<Int>` 就是 Int），所以**不需要传转换器**。
 * 3. 白名单累加：每次读取都把属性名记进 [known]（**无论 XML 里有没有写**），
 *    工厂跑完后 `attributes.keys - known` 就是「这个组件不认识的属性」。
 *    没有反射，节点看不到自己声明了什么，只能靠这条反过来推导。
 *
 * 节点**不得**持有本对象的引用：节点树会被 `LayoutCache` 跨界面实例复用，
 * 拖住 context 就等于拖住整棵解析现场。
 */
class NodeContext internal constructor(
    val tag: String,
    val attributes: Map<String, String>,
    val childSpecs: List<XmlElement>,
    val line: Int,
    private val registry: EngineRegistry,
) {

    /** 这个组件声明过的属性名（读取时登记，与 XML 是否写了无关）。 */
    @PublishedApi
    internal val known = LinkedHashSet<String>()

    /** 递归构建子节点（懒加载：叶子节点不会触发 children 构建）。 */
    val children: List<Node> by lazy { childSpecs.map { registry.buildNode(it) } }

    // ---------------- 原始读取（结构性属性用，不参与绑定） ----------------

    fun has(name: String): Boolean {
        known += name
        return attributes.containsKey(name)
    }

    fun optional(name: String): String? {
        known += name
        return attributes[name]
    }

    fun require(name: String): String {
        known += name
        return attributes[name] ?: fail("缺少必需属性 '$name'", name)
    }

    /**
     * 动作引用：结构性属性，注册期就要能校验「动作已注册」，所以不支持绑定。
     */
    fun action(name: String): ActionRef? {
        known += name
        val raw = attributes[name] ?: return null
        return BindingParser.parseAction(raw, tag = tag, line = line)
    }

    // ---------------- 属性值读取（类型由 T 决定，不传转换器） ----------------

    /**
     * 读取一个可选属性。类型取自调用点声明的 `AttrValue<T>`：
     * ```
     * val maxLines: AttrValue<Int>? = context.attr("maxLines")   // "3" → 3
     * ```
     *
     * 返回 null 表示「用户没写这个属性」，与「写了但求值为 null」是两回事：
     * 前者渲染器用默认值，后者由求值结果为 null 表达。
     */
    inline fun <reified T : Any> attr(name: String): AttrValue<T>? = attrOf(name, attrType<T>())

    inline fun <reified T : Any> need(name: String): AttrValue<T> =
        attr<T>(name) ?: fail("缺少必需属性 '$name'", name)

    /**
     * 读取一个必须可写回的属性（双向绑定）：字面量直接报错，因为字面量没有路径。
     */
    inline fun <reified T : Any> writable(name: String): AttrValue<T>? =
        writableOf(name, attrType<T>())

    inline fun <reified T : Any> needWritable(name: String): AttrValue<T> =
        writable<T>(name) ?: fail("缺少必需属性 '$name'", name)

    @PublishedApi
    internal fun <T> attrOf(name: String, type: AttrType<T>): AttrValue<T>? {
        known += name
        val raw = attributes[name] ?: return null
        val site = AttrSite(tag, name, line)
        return when (val expression = parseExpression(name, raw)) {
            is BindingExpression.Literal -> AttrValue(AttrSource.Const(expression.raw), type, site)
            is BindingExpression.Path -> AttrValue(AttrSource.Var(expression.path), type, site)
        }
    }

    @PublishedApi
    internal fun <T> writableOf(name: String, type: AttrType<T>): AttrValue<T>? {
        val attr = attrOf(name, type) ?: return null
        if (!attr.isWritable) {
            fail("属性 '$name' 必须是绑定表达式，例如 $name=\"{{value}}\"（字面量无法写回数据）", name)
        }
        return attr
    }

    // ---------------- 子节点 ----------------

    /** 构建某个子元素下的所有节点（用于 Then / Else 这类非注册标签）。 */
    fun buildChildrenOf(spec: XmlElement): List<Node> = spec.children.map { registry.buildNode(it) }

    fun requireBranch(branchTag: String): BranchNode =
        findBranch(branchTag, required = true) ?: fail("<$tag> 缺少必需的分支 <$branchTag>")

    fun optionalBranch(branchTag: String): BranchNode? = findBranch(branchTag, required = false)

    fun ensureNoChildren() {
        if (childSpecs.isNotEmpty()) {
            fail("不允许包含子节点，实际包含 <${childSpecs.first().tag}>")
        }
    }

    fun ensureChildren() {
        if (childSpecs.isEmpty()) fail("至少需要一个子节点")
    }

    /** 只允许出现指定的子标签（`<If>` 的 Then / Else）。 */
    fun ensureChildTags(vararg allowed: String) {
        childSpecs.forEach { spec ->
            if (spec.tag !in allowed) {
                fail("<$tag> 只允许包含 ${allowed.joinToString(" / ") { "<$it>" }}，实际出现 <${spec.tag}>")
            }
        }
    }

    /** 工厂执行完后由注册表调用：没被读取过的属性就是组件不认识的属性。 */
    internal fun ensureAllConsumed() {
        val unknown = attributes.keys.filterNot { it in known }
        if (unknown.isEmpty()) return
        throw XmlUiException(
            "不支持的属性 ${unknown.joinToString { "'$it'" }}。<${tag}> 支持的属性: ${
                known.sorted().joinToString(", ")
            }",
            tag = tag,
            attribute = unknown.first(),
            line = line,
        )
    }

    fun fail(reason: String, attribute: String? = null): Nothing =
        throw XmlUiException(reason, tag = tag, attribute = attribute, line = line)

    private fun findBranch(branchTag: String, required: Boolean): BranchNode? {
        var found: BranchNode? = null
        childSpecs.forEach { spec ->
            if (spec.tag == branchTag) {
                if (found != null) fail("只允许一个 <$branchTag> 分支")
                if (spec.attributes.isNotEmpty()) fail("<$branchTag> 不接受属性")
                found = BranchNode(branchTag, buildChildrenOf(spec), spec.line)
            }
        }
        if (found == null && required) return null
        return found
    }

    private fun parseExpression(name: String, raw: String): BindingExpression = try {
        BindingParser.parseAttribute(raw)
    } catch (e: XmlUiException) {
        throw XmlUiException(e.reason, tag = tag, attribute = name, line = line)
    }
}
