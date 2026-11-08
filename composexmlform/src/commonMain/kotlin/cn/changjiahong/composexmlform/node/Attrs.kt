package cn.changjiahong.composexmlform.node

import cn.changjiahong.composexmlform.binding.ActionRef
import cn.changjiahong.composexmlform.binding.BindingParser
import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.store.readSegment

/** config 在绑定路径里的命名空间名。config 是**解析期资源**，运行时不存在这个变量。 */
internal const val CONFIG_KEY = "config"

/**
 * 动作引用的属性类型：**两个方法都永远不该被调用**。
 *
 * 它们的存在只为把「误用成普通属性类型」变成一句准确的报错：
 * - 写 `attrs.valOf<ActionRef>("onTap")` → literal 说清该怎么读；
 * - 写 `onTap="{{fn}}"` → adapt 说清动作不能绑定。
 *
 * 正常路径走 [Attrs.actionOf]，构造的是 [AttrSource.Typed]，两个方法都不会被触发。
 */
private val ActionType: AttrType<ActionRef> = object : AttrType<ActionRef> {

    override fun literal(raw: String, site: AttrSite): ActionRef =
        site.fail("动作引用请用 actionOf(\"属性名\") 读取，不能用 valOf")

    override fun adapt(value: Any?, site: AttrSite): ActionRef? =
        site.fail("动作引用不能绑定到数据路径，只能直接写 name()")
}

/**
 * 一个已经解析过的属性条目：XML 原文 + 来源 + 坐标。
 *
 * **类型不在这里**：属性该是什么类型由节点字段的声明决定（`ValAttr<Int>` 就是 Int），
 * 解析期手上只有字符串。所以条目是「未定型」的，[Attrs.valOf] / [Attrs.varOf]
 * 那一刻才定型。这不是设计取舍，是 XML 不带类型信息的必然结果。
 */
class AttrEntry internal constructor(

    /** XML 里写的原文。`item` / `index` / `key` / `action` 这类结构性属性直接用它。 */
    val raw: String,

    internal val source: AttrSource,

    /** 报错坐标（标签 / 属性名 / 行号），字面量和绑定值共用。 */
    val site: AttrSite,
)

/**
 * 一个节点的全部属性。**本质就是一个 Map**：`属性名 -> AttrEntry`。
 *
 * 它取代了原来的 `NodeContext`，只保留与属性有关的两件事：
 *
 * 1. 按节点声明的类型取值：[valOf] 得到只读槽位，[varOf] 得到可写回槽位；
 * 2. 记录「哪些属性被读过」，供解析器反推白名单（[assertAllConsumed]）。
 *
 * 子节点、分支、注册表都不在这里 —— 它们是解析器的结构问题，不是属性问题。
 * `children` 通过构造参数传入，注册表也只在解析期出现，因此节点树脱离解析现场
 * 之后仍然完整，渲染期不再需要本对象。
 *
 * 为什么第 2 项搬不走：KMP 没有反射，节点支持哪些属性只能从「读了哪些名字」反推。
 * 要删掉它，就得同时接受 `fontSzie="20"` 静默无效。
 *
 * [config] 是**解析期资源**：`{{config.x}}` 在这里就地求值成常量，运行时不存在 config。
 */
class Attrs internal constructor(
    private val attributeMap: Map<String, AttrEntry>,
    val tag: String,
    val line: Int,
    private val config: Map<String, Any?> = emptyMap(),
    private val actionUsages: MutableList<ActionUsage> = mutableListOf(),
    private val fieldRules: MutableList<FieldRules> = mutableListOf(),
) : Map<String, AttrEntry> by attributeMap {

    /** 被节点读取过的属性名（无论 XML 里有没有写）。 */
    private val consumed = HashSet<String>()

    /**
     * XML 里声明的 id；没写就是 null（节点会自己生成）。
     *
     * `id` 是框架级属性，每个节点都要读，所以在构造时一次性登记进白名单，
     * 节点作者不需要为它写任何代码，也不会因为漏读而被报「不支持的属性 'id'」。
     */
    val id: String? = attributeMap["id"]?.raw

    init {
        consumed += "id"
    }

    // ---------------- 按声明类型取值 ----------------

    /**
     * 读取一个只读属性。类型取自调用点声明的 `ValAttr<T>`：
     * ```
     * val maxLines: ValAttr<Int>? = attrs.valOf("maxLines")   // "3" → 3
     * ```
     *
     * 返回 null 表示「XML 里没写这个属性」。属性都带默认值，所以缺省不是错误：
     * 节点写 `?: valAttr(默认值)` 即可。真正必填时，节点自己用 [fail] 报。
     *
     * `{{config.x}}` 在这里被**求值成常量**（[AttrSource.Typed]）：类型转换也发生在解析期，
     * 所以 `config.count` 是字符串而属性是 `ValAttr<Int>` 时，解析期就报错。
     */
    inline fun <reified T : Any> valOf(name: String): ValAttr<T>? =
        valOfImpl(name, attrType<T>())

    /**
     * 读取一个可写回属性（双向绑定）：必须是 `{{path}}`，字面量在这里报错。
     *
     * 返回 [VarAttr] 而不是 [ValAttr]，渲染器拿到就具备写入口，
     * 不需要再判断「这个值能不能写」。
     */
    inline fun <reified T : Any> varOf(name: String): VarAttr<T>? =
        varOfImpl(name, attrType<T>())

    @PublishedApi
    internal fun <T> valOfImpl(name: String, type: AttrType<T>): ValAttr<T>? {
        consumed += name
        val entry = attributeMap[name] ?: return null
        val configPath = (entry.source as? AttrSource.Var)?.path?.takeIf { it.first == CONFIG_KEY }
        if (configPath != null) {
            val value = resolveConfig(configPath, entry.site)
            // 走 adapt 而不是直接 Typed：类型不符要在解析期报错，不要等到渲染期
            val adapted = type.adapt(value, entry.site)
            return ValAttr(AttrSource.Typed(adapted), type, entry.site)
        }
        // 常量在 ValAttr 构造期就定型，`maxLines="三"` 因此仍然在解析期报错。
        return ValAttr(entry.source, type, entry.site)
    }

    @PublishedApi
    internal fun <T> varOfImpl(name: String, type: AttrType<T>): VarAttr<T>? {
        consumed += name
        val entry = attributeMap[name] ?: return null
        val path = (entry.source as? AttrSource.Var)?.path
            ?: fail(
                "属性 '$name' 必须是绑定表达式，例如 $name=\"{{value}}\"（字面量无法写回数据）",
                name,
            )
        if (path.first == CONFIG_KEY) {
            fail(
                "config 是只读资源，不能作为双向绑定目标：'$name=\"{{${path.raw}}}\"'",
                name,
            )
        }
        return VarAttr(path, type, entry.site)
    }

    // ---------------- 结构性属性（字符串，不做类型转换） ----------------

    /** 结构性属性的原文：`item` / `index` / `key` 是变量名或字段名，不是值。 */
    fun raw(name: String): String? {
        consumed += name
        return attributeMap[name]?.raw
    }

    /**
     * 动作引用。属性名由节点自己定（`action` / `onTap` / `onLongPress`…），不再是框架保留字。
     *
     * 为什么单独一个方法而不是 `valOf<ActionRef>("action")`：
     * 1. 动作**不能绑定**（`onTap="{{fn}}"` 解析期就要拒），普通 `valOf` 允许 `{{path}}`；
     * 2. 要顺手把「谁在哪儿引用了什么」记进 [ActionUsage]，供运行时构造期批量校验；
     * 3. 语法 `name()` 需要专用解析器，[AttrType.literal] 那条路径给不出准确报错。
     */
    fun actionOf(name: String): ValAttr<ActionRef>? {
        consumed += name
        val entry = attributeMap[name] ?: return null
        if (entry.source is AttrSource.Var) {
            fail(
                "动作引用不能绑定数据，必须直接写成 $name=\"functionName()\"",
                name,
            )
        }
        val ref = BindingParser.parseAction(
            raw = entry.raw,
            tag = entry.site.tag,
            attribute = entry.site.attribute,
            line = entry.site.line,
        )
        actionUsages += ActionUsage(ref, entry.site)
        return ValAttr(AttrSource.Typed(ref), ActionType, entry.site)
    }

    /**
     * 收集一条字段校验规则（解析期）。
     *
     * 和 [actionOf] 同一个套路：**规则来自 XML，只能在这里看到**，所以解析期收进清单，
     * 运行时由 `FormState.attach(...)` 一次装载。不在运行时遍历树找规则 ——
     * 那样拿不到 `value` 的绑定路径（规则挂在路径上，见 [FieldRules]）。
     */
    internal fun recordFieldRule(
        path: cn.changjiahong.composexmlform.binding.ValuePath,
        label: String?,
        required: Boolean,
        minLength: Int?,
        maxLength: Int?,
        pattern: String?,
    ) {
        if (pattern != null) {
            try {
                Regex(pattern)
            } catch (e: IllegalArgumentException) {
                fail("pattern 不是合法的正则表达式：$pattern（${e.message}）", "pattern")
            }
        }
        fieldRules += FieldRules(
            path = path,
            label = label,
            required = required,
            minLength = minLength,
            maxLength = maxLength,
            pattern = pattern,
        )
    }

    // ---------------- 诊断 ----------------

    fun fail(reason: String, attribute: String? = null): Nothing =
        throw XmlUiException(reason, tag = tag, attribute = attribute, line = line, code = Code.TypeMismatch)

    /**
     * 工厂执行完之后由注册表调用：没被读取过的属性就是组件不认识的属性。
     *
     * 不在这里抛：解析器要收集**全部**诊断再决定抛不抛（`ParseMode.Strict`），
     * 而且 `Tolerant` 下这条只是 Warning —— 属性被忽略，节点照常建出来。
     */
    internal fun assertAllConsumed(report: (XmlUiException) -> Unit) {
        val unknown = attributeMap.keys.filterNot { it in consumed }
        if (unknown.isEmpty()) return
        report(
            XmlUiException(
                "不支持的属性 ${unknown.joinToString { "'$it'" }}。<${tag}> 支持的属性: ${
                    consumed.sorted().joinToString(", ")
                }",
                tag = tag,
                attribute = unknown.first(),
                line = line,
                code = Code.UnknownAttribute,
            ),
        )
    }

    /**
     * 沿 config 路径取值。缺失时报错并列出可用键 —— 拼错 `{{config.region}}` 不再静默空白。
     */
    private fun resolveConfig(path: ValuePath, site: AttrSite): Any? {
        var current: Any? = config
        var index = 1 // 第 0 段是 "config" 本身
        while (index < path.segments.size) {
            val next = readSegment(current, path.segments[index])
            if (next == null) {
                site.fail(
                    "config 里没有 '${path.raw}'，可用: ${describeAvailable(current)}",
                )
            }
            current = next
            index++
        }
        if (current == null) site.fail("config 里的 '${path.raw}' 是 null")
        return current
    }

    private fun describeAvailable(container: Any?): String = when (container) {
        is Map<*, *> -> container.keys.joinToString(", ").ifEmpty { "config 为空" }
        is List<*> -> "（是列表，下标 0..${container.size - 1}）"
        null -> "config 为空"
        else -> container::class.simpleName ?: "?"
    }

    companion object {

        /** 空属性包：程序化构造节点的默认入口（`RatingBarNode()` 这种）。 */
        val Empty: Attrs = Attrs(emptyMap(), tag = "", line = 0)

        /**
         * 程序化构造：直接给已定型的值，不经过字符串往返。
         *
         * ```
         * TextNode(Attrs.literal("value" to "你好", "fontSize" to 14f))
         * ```
         *
         * 值走 [AttrSource.Typed]，所以 `14f` 不会被当成字符串再解析一遍
         * （XML 里的 `fontSize="14"` 仍要过 `FloatType.literal`）。
         */
        fun literal(vararg pairs: Pair<String, Any?>): Attrs {
            val entries = LinkedHashMap<String, AttrEntry>(pairs.size)
            pairs.forEach { (name, value) ->
                val site = AttrSite.Programmatic
                entries[name] = AttrEntry(
                    raw = value?.toString() ?: "",
                    source = AttrSource.Typed(value),
                    site = site,
                )
            }
            return Attrs(entries, tag = "", line = 0)
        }
    }
}
