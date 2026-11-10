package cn.changjiahong.composexmlform.engine.render

import androidx.compose.runtime.Composable
import cn.changjiahong.composexmlform.engine.node.Node
import kotlin.reflect.KClass

/**
 * 一种节点 → 一个 Composable。
 *
 * 是 `fun interface` 而不是抽象类：它没有状态、没有可复写行为，「必须继承」只带来
 * 写法负担（`class X : NodeRenderer<T>()`）。旧实现里基类的 `RenderChild` 成员与顶层
 * `RenderNode` 是同一件事的两个名字，一并去掉 —— 「怎么画子节点」只留一个入口。
 *
 * **递归策略在容器手里**，引擎不替它们遍历：`Foreach` 把同一份子节点渲染 N 次
 * （每行不同作用域），`If` 只渲染一支，`Column` 按顺序全画。它们的共同点只有
 * 「某个时刻要画一个子节点」，那就调 `RenderNode`。
 */
fun interface NodeRenderer<T : Node> {

    @Composable
    fun Render(node: T)
}

/**
 * 类型 → 渲染器的**不可变注册表**，与 [cn.changjiahong.composexmlform.engine.node.NodeTypes]
 * 同构（重名失败、[replacing] 显式覆盖）。
 *
 * 为什么不可变：解析出来的树，其节点类型在整棵树寿命内不能漂移 ——
 * 否则「解析时是 Text、渲染时找不到渲染器」只会在特定时序下复现。要加渲染器就
 * `plus` 出一份新的，`DynamicLayout` 的 remember key 会带它重建。
 *
 * 按 `KClass` 精确匹配，**不做父类链回溯**：KMP 的 common stdlib 没有
 * `KClass.supertypes`（那是 `kotlin-reflect` / JVM 的东西）。真想要兜底，
 * 用 [withFallback] 显式声明一个「谁都认」的渲染器。
 */
class Renderers private constructor(
    private val byType: Map<KClass<*>, NodeRenderer<*>>,
    private val fallback: NodeRenderer<Node>?,
) {

    val size: Int get() = byType.size

    val isEmpty: Boolean get() = byType.isEmpty() && fallback == null

    /**
     * 注册一个渲染器。**重名直接失败** —— 两个 `Text` 渲染器同时存在时「谁生效」
     * 取决于注册顺序，那是最难查的一类 bug。
     */
    fun <T : Node> plus(type: KClass<T>, renderer: NodeRenderer<T>): Renderers {
        require(type !in byType) {
            "『${type.simpleName}』已经有渲染器了；要替换请用 replacing()"
        }
        return Renderers(byType + (type to renderer), fallback)
    }

    /** 有意替换一个已有渲染器（宿主覆盖内置组件）。 */
    fun <T : Node> replacing(type: KClass<T>, renderer: NodeRenderer<T>): Renderers =
        Renderers(byType + (type to renderer), fallback)

    fun minus(type: KClass<*>): Renderers = Renderers(byType - type, fallback)

    /** 谁都认的兜底渲染器。只在精确匹配失败后用，不会盖掉任何已注册的类型。 */
    fun withFallback(renderer: NodeRenderer<Node>): Renderers = Renderers(byType, renderer)

    @Suppress("UNCHECKED_CAST")
    fun <T : Node> rendererOrNull(type: KClass<T>): NodeRenderer<T>? = byType[type] as NodeRenderer<T>?

    @Suppress("UNCHECKED_CAST")
    fun rendererFor(node: Node): NodeRenderer<Node>? =
        (byType[node::class] ?: fallback) as NodeRenderer<Node>?

    operator fun plus(other: Renderers): Renderers {
        var merged = this
        for ((type, renderer) in other.byType) {
            require(type !in merged.byType) {
                "『${type.simpleName}』已经有渲染器了；合并前先 removing() 或 replacing()"
            }
            merged = Renderers(merged.byType + (type to renderer), merged.fallback)
        }
        return Renderers(merged.byType, other.fallback ?: merged.fallback)
    }

    override fun toString(): String = "Renderers(${byType.size}${fallback?.let { " +fallback" } ?: ""})"

    companion object {

        val Empty: Renderers = Renderers(emptyMap(), null)

        fun of(vararg pairs: Pair<KClass<*>, NodeRenderer<*>>): Renderers {
            var result = Empty
            for ((type, renderer) in pairs) result = result.addRaw(type, renderer)
            return result
        }
    }

    private fun addRaw(type: KClass<*>, renderer: NodeRenderer<*>): Renderers =
        Renderers(byType + (type to renderer), fallback)
}

/** 声明式写法：`renderers { on(TextNode::class) { … } }`。 */
fun renderers(block: RenderersBuilder.() -> Unit): Renderers =
    RenderersBuilder().apply(block).build()

class RenderersBuilder internal constructor() {

    private var current: Renderers = Renderers.Empty

    fun <T : Node> on(type: KClass<T>, renderer: NodeRenderer<T>) {
        current = current.plus(type, renderer)
    }

    fun <T : Node> on(type: KClass<T>, block: @Composable (T) -> Unit) {
        current = current.plus(type, NodeRenderer { block(it) })
    }

    fun fallback(renderer: NodeRenderer<Node>) {
        current = current.withFallback(renderer)
    }

    internal fun build(): Renderers = current
}
