package cn.changjiahong.composexmlform.node

import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.store.StoreOwner
import cn.changjiahong.composexmlform.store.WriteSource

/**
 * 属性来源坐标：标签 / 属性名 / 行号。
 *
 * 之所以把它一路带在属性值上，而不是只留在解析期：绑定值要到渲染期
 * （甚至更晚，取决于数据何时变化）才求值。没有坐标，运行期的类型错误只能报
 * 「某个地方的值不对」，而这正是这类引擎最难排查的错误。
 */
data class AttrSite(val tag: String, val attribute: String, val line: Int) {

    /** 属性级失败一律是「类型不匹配」（值写错、转换失败、不可写），码固定为 TypeMismatch。 */
    fun fail(reason: String): Nothing =
        throw XmlUiException(reason, tag = tag, attribute = attribute, line = line, code = Code.TypeMismatch)

    companion object {
        /** 程序化构造节点时的坐标：没有 XML 来源，报错里也就没有标签和行号。 */
        val Programmatic = AttrSite("", "", 0)
    }
}

/**
 * 属性值的来源。三种，对使用者透明：
 *
 * - [Const]：XML 里写的字面量，**原样保存字符串**，转换在 [ValAttr] 构造期完成，
 *   所以 `maxLines="三"` 仍然在解析期报错。XML 不写单位（`sp` / `dp`），
 *   单位由属性声明的类型决定。
 * - [Typed]：程序化构造时给出的、**已经是 T 的值**，不需要也不可能再转换。
 * - [Var]：`{{path}}`，运行期按当前作用域取值。
 */
internal sealed interface AttrSource {

    class Const(val raw: String) : AttrSource

    class Typed(val value: Any?) : AttrSource

    class Var(val path: ValuePath) : AttrSource
}

/**
 * 一个属性的值，**只承诺读**。
 *
 * 之所以按「能力」而不是「来源」分类型：属性值可以来自字面量，也可以来自 `{{path}}`，
 * 但**只读槽位同样经常绑变量**（`<Text value="{{name}}"/>`）。所以「值是不是变量」
 * 不能用来区分类型；能区分的是「这个槽位会不会被写回」——写回才是少数。
 *
 * - 只读槽位：[ValAttr]，来源可以是 [AttrSource.Const] / [AttrSource.Typed] / [AttrSource.Var]。
 * - 可写回槽位：[VarAttr]，来源必须是 `{{path}}`（字面量没有可写回的目标）。
 *
 * 用 `sealed` 而不是开放继承：新增一种值来源时，编译期能强制所有分支都被处理。
 */
sealed interface AttrValue<T> {

    val site: AttrSite

    /** 绑定路径；非绑定来源为 null。诊断与序列化用。 */
    val path: ValuePath?

    /** 按给定作用域求值。读 state 即订阅，所以只在 composition 里调用会建立订阅。 */
    fun read(env: StoreOwner): T?
}

/**
 * 只读槽位：渲染器只能读，写回在类型上就不存在。
 *
 * 实现约束（改动前必读）：
 * - 实例**不可变**，且**不允许添加任何依赖作用域的求值缓存**。同一个 `ValAttr`
 *   实例不仅跨重组复用，还会在 `Foreach` 里被不同作用域反复求值（一个节点渲染 N 行），
 *   缓存了某次结果就会让第 2 行读到第 1 行的值。
 * - [constant] 是唯一允许的缓存：它只由字面量 / 程序化构造决定，与作用域无关。
 */
class ValAttr<T> internal constructor(
    internal val source: AttrSource,
    private val type: AttrType<T>,
    override val site: AttrSite,
) : AttrValue<T> {

    /** 非变量来源的定型结果（绑定恒为 null）：构造期算一次，运行期零转换。 */
    internal val constant: T? = when (val origin = source) {
        is AttrSource.Const -> type.literal(origin.raw, site)
        is AttrSource.Typed -> cast(origin.value)
        is AttrSource.Var -> null
    }

    override val path: ValuePath? get() = (source as? AttrSource.Var)?.path

    override fun read(env: StoreOwner): T? = when (val origin = source) {
        is AttrSource.Var -> type.adapt(env.read(origin.path), site)
        else -> constant
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(value: Any?): T? = value as T

    override fun toString(): String = when (val origin = source) {
        is AttrSource.Const -> "ValAttr('${origin.raw}')"
        is AttrSource.Typed -> "ValAttr(${origin.value})"
        is AttrSource.Var -> "ValAttr({{${origin.path.raw}}})"
    }
}

/**
 * 可写回槽位：双向绑定用。
 *
 * 它是独立类型而不是 [ValAttr] 的子类，是因为「能写」这件事只需要对少数槽位成立
 * （TextField / Select / RatingBar）。渲染器拿到 [VarAttr] 就不必再检查「这个值能不能写」——
 * 类型已经保证了；`value="固定值"` 这种写法在解析期就被 `writable` 拒掉，
 * 不会留下「运行期才报错」的路径。
 */
class VarAttr<T> internal constructor(
    private val target: ValuePath,
    private val type: AttrType<T>,
    override val site: AttrSite,
) : AttrValue<T> {

    override val path: ValuePath get() = target

    override fun read(env: StoreOwner): T? = type.adapt(env.read(target), site)

    /**
     * 写回数据源。来源恒为 [WriteSource.UserBinding]：
     * 能走到这里的只有渲染器里用户的输入 / 选择，业务代码写数据走 `BindableData.set`。
     */
    fun write(env: StoreOwner, value: T?) {
        env.write(target, value, WriteSource.UserBinding)
    }

    override fun toString(): String = "VarAttr({{${target.raw}}})"
}

/**
 * 程序化构造一个只读属性：值已经是 T，不再经过字符串转换。
 *
 * 它让节点可以脱离 XML 构造（单元测试、IDE 预览、代码里直接拼界面）：
 * ```
 * TextNode(Attrs.literal("value" to "你好", "fontSize" to 14f))
 * ```
 */
inline fun <reified T : Any> valAttr(
    value: T,
    site: AttrSite = AttrSite.Programmatic,
): ValAttr<T> = valAttrOf(value, attrType<T>(), site)

@PublishedApi
internal fun <T> valAttrOf(value: Any?, type: AttrType<T>, site: AttrSite): ValAttr<T> =
    ValAttr(AttrSource.Typed(value), type, site)
