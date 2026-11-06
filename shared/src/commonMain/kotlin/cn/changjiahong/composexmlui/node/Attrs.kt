package cn.changjiahong.composexmlui.node

import cn.changjiahong.composexmlui.binding.ActionRef
import cn.changjiahong.composexmlui.binding.BindingParser
import cn.changjiahong.composexmlui.core.XmlUiException

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
 */
class Attrs internal constructor(
    private val attributeMap: Map<String, AttrEntry>,
    val tag: String,
    val line: Int,
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
        return VarAttr(path, type, entry.site)
    }

    // ---------------- 结构性属性（字符串，不做类型转换） ----------------

    /** 结构性属性的原文：`item` / `index` / `key` 是变量名或字段名，不是值。 */
    fun raw(name: String): String? {
        consumed += name
        return attributeMap[name]?.raw
    }

    /** 动作引用：注册期就要校验「动作已注册」，所以不支持绑定。 */
    fun action(name: String): ActionRef? {
        consumed += name
        val raw = attributeMap[name]?.raw ?: return null
        return BindingParser.parseAction(raw, tag = tag, line = line)
    }

    // ---------------- 诊断 ----------------

    fun fail(reason: String, attribute: String? = null): Nothing =
        throw XmlUiException(reason, tag = tag, attribute = attribute, line = line)

    /** 工厂执行完之后由注册表调用：没被读取过的属性就是组件不认识的属性。 */
    internal fun assertAllConsumed() {
        val unknown = attributeMap.keys.filterNot { it in consumed }
        if (unknown.isEmpty()) return
        throw XmlUiException(
            "不支持的属性 ${unknown.joinToString { "'$it'" }}。<${tag}> 支持的属性: ${
                consumed.sorted().joinToString(", ")
            }",
            tag = tag,
            attribute = unknown.first(),
            line = line,
        )
    }
}
