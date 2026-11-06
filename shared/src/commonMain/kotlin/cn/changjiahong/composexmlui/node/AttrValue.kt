package cn.changjiahong.composexmlui.node

import cn.changjiahong.composexmlui.binding.ValuePath
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.store.StoreOwner

/**
 * 属性来源坐标：标签 / 属性名 / 行号。
 *
 * 之所以把它一路带在 [AttrValue] 上，而不是只留在解析期：绑定值要到渲染期
 * （甚至更晚，取决于数据何时变化）才求值。没有坐标，运行期的类型错误只能报
 * 「某个地方的值不对」，而这正是这类引擎最难排查的错误。
 */
data class AttrSite(val tag: String, val attribute: String, val line: Int) {

    fun fail(reason: String): Nothing =
        throw XmlUiException(reason, tag = tag, attribute = attribute, line = line)
}

/**
 * 一个属性值的来源，只有两种，且对使用者完全透明。
 *
 * - [Const]：XML 里写的字面量，**原样保存字符串**。类型转换在 [AttrValue] 构造期完成，
 *   所以 `maxLines="三"` 仍然在解析期报错。XML 里不写单位（`sp` / `dp`），
 *   单位由属性声明的类型决定。
 * - [Var]：`{{path}}`，运行期按当前作用域取值。
 *
 * 为什么不分两个类：常量与变量唯一的差别就是「要不要读作用域」，这一点用一次
 * `when` 就够；分成两个类型会让每个渲染器、每个读写入口都跟着分叉。
 */
internal sealed interface AttrSource {

    class Const(val raw: String) : AttrSource

    class Var(val path: ValuePath) : AttrSource
}

/**
 * 一个属性的值。
 *
 * 类型由 `T` 决定，转换规则由 [AttrType] 从 `T` 自动推导 —— 节点定义里不出现转换器：
 * ```
 * val maxLines: AttrValue<Int>? = context.attr("maxLines")   // "3" → 3
 * val text: AttrValue<String> = context.need("value")        // 原样
 * val user: AttrValue<User>? = context.attr("user")          // 只能 {{user}}
 * ```
 *
 * 实现约束（改动前必读）：
 * - 实例**不可变**，且**不允许添加任何依赖作用域的求值缓存**。节点树被 `LayoutCache`
 *   跨界面实例共享，一旦缓存了某次求值的结果，两个界面就会互相串数据。
 * - [constant] 是唯一允许的缓存：它只由 XML 字面量决定，与作用域无关。
 */
class AttrValue<T> internal constructor(
    internal val source: AttrSource,
    private val type: AttrType<T>,
    val site: AttrSite,
) {

    /** 字面量的定型结果（变量恒为 null）：构造期算一次，运行期零转换。 */
    internal val constant: T? =
        (source as? AttrSource.Const)?.let { type.literal(it.raw, site) }

    /** 是否可写回：只有 `{{path}}` 能写，字面量不能。双向绑定据此判断。 */
    val isWritable: Boolean get() = source is AttrSource.Var

    /** 绑定路径；字面量为 null。 */
    val path: ValuePath? get() = (source as? AttrSource.Var)?.path

    internal fun read(env: StoreOwner): T? = when (source) {
        is AttrSource.Const -> constant
        is AttrSource.Var -> type.adapt(env.read(source.path), site)
    }

    internal fun write(env: StoreOwner, value: T?) {
        when (source) {
            is AttrSource.Const -> site.fail("属性值是固定的（'${source.raw}'），无法写回数据")
            is AttrSource.Var -> env.write(source.path, value)
        }
    }

    override fun toString(): String = when (source) {
        is AttrSource.Const -> "AttrValue('${source.raw}')"
        is AttrSource.Var -> "AttrValue({{${source.path.raw}}})"
    }
}
