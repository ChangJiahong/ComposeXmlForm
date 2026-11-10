package cn.changjiahong.composexmlform.api

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlform.ComposeXmlForm
import cn.changjiahong.composexmlform.engine.node.AttrDef
import cn.changjiahong.composexmlform.engine.node.AttrSet
import cn.changjiahong.composexmlform.engine.node.ChildRule
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.node.NodeType
import cn.changjiahong.composexmlform.engine.node.NodeTypes
import cn.changjiahong.composexmlform.engine.node.StructuralTypes
import cn.changjiahong.composexmlform.engine.render.NodeRenderer
import cn.changjiahong.composexmlform.engine.render.Renderers
import cn.changjiahong.composexmlform.engine.render.StructuralRenderers
import kotlin.reflect.KClass

/**
 * 一个组件包：把「注册几样东西」装成一个可复用的值。
 *
 * `fun interface` 而不是 `interface`：单个 lambda 就是一个小模块，不必先起名字。
 * 内置组件（[cn.changjiahong.composexmlform.DefaultComponents]）走的是和第三方扩展
 * **完全相同**的注册路径，没有任何特权。
 */
fun interface ComponentModule {

    fun install(registry: EngineRegistry)
}

/**
 * 引擎注册表：**标签表 + 渲染器表**，两者总是一起变。
 *
 * 为什么把这两样绑在一个对象里：它们必须同步 —— 「解析时是 Text、渲染时找不到渲染器」
 * 只在特定时序下复现，而那时序几乎总是「注册了标签但忘了渲染器」。合成一个动作就没有这个空档。
 *
 * [version] 是给 `DynamicLayout` 的 `remember` key 用的：宿主在界面组合完之后再 `install` 一个包，
 * 版本一变就重新解析并重建运行时。读它即订阅。
 *
 * 表本身是不可变的（[NodeTypes] / [Renderers]），注册是「换成一张新表 + 版本加一」。
 * 因此一次解析拿到的树，其标签表在整棵树寿命内不会漂移。
 */
class EngineRegistry {

    private val typesState = mutableStateOf(StructuralTypes.All)
    private val renderersState = mutableStateOf(StructuralRenderers.All)
    private val versionState = mutableStateOf(0L)

    val types: NodeTypes get() = typesState.value

    val renderers: Renderers get() = renderersState.value

    /** 每次成功注册加一。读了就订阅。 */
    val version: Long get() = versionState.value

    val tags: Set<String> get() = types.tags

    /**
     * 注册一个标签：属性表 + 子节点规则 + 构造器 + 渲染器。
     *
     * [nodeClass] 显式传而不是从 `T` 里取：Kotlin 的 `T::class` 要求 `reified`，
     * 而 `reified` 又会把 `register` 变成 `inline`，进而碰不到注册表的私有状态。
     * 调用处写 `TextNode::class` 只多五个字符，换来的是没有反射、没有 inline 约束。
     *
     * 重名**直接失败**：两张表都按「重名即错误」的规则，这里不额外判断 ——
     * 让 [NodeTypes.plus] / [Renderers.plus] 抛，消息里已经写清了该怎么换。
     */
    fun <T : Node> register(
        nodeClass: KClass<T>,
        tag: String,
        attributes: List<AttrDef<*>> = emptyList(),
        childRule: ChildRule = ChildRule.None,
        create: (id: String, attrs: AttrSet, children: List<Node>) -> T,
        renderer: NodeRenderer<T>,
    ): EngineRegistry {
        val type = NodeType(tag, attributes, childRule, create)
        typesState.value = typesState.value.plus(type)
        renderersState.value = renderersState.value.plus(nodeClass, renderer)
        versionState.value += 1L
        return this
    }

    /** 有意替换一个已注册的标签（宿主覆盖内置组件）。 */
    fun <T : Node> replace(
        nodeClass: KClass<T>,
        tag: String,
        attributes: List<AttrDef<*>> = emptyList(),
        childRule: ChildRule = ChildRule.None,
        create: (id: String, attrs: AttrSet, children: List<Node>) -> T,
        renderer: NodeRenderer<T>,
    ): EngineRegistry {
        val type = NodeType(tag, attributes, childRule, create)
        typesState.value = typesState.value.replacing(type)
        renderersState.value = renderersState.value.replacing(nodeClass, renderer)
        versionState.value += 1L
        return this
    }

    /** 只换渲染器（标签与属性不动）。 */
    fun <T : Node> repaint(nodeClass: KClass<T>, renderer: NodeRenderer<T>): EngineRegistry {
        renderersState.value = renderersState.value.replacing(nodeClass, renderer)
        versionState.value += 1L
        return this
    }

    fun install(module: ComponentModule): EngineRegistry {
        module.install(this)
        return this
    }

    fun install(vararg modules: ComponentModule): EngineRegistry {
        modules.forEach { it.install(this) }
        return this
    }

    override fun toString(): String = "EngineRegistry(${types.size} 个标签, v$version)"
}

/**
 * 当前注册表。
 *
 * 默认值是全局那一份（含内置组件），所以 `preview(...)` / 单个 `DynamicLayout` 不传也不崩。
 * 需要隔离（测试、多实例）时用 [ComposeXmlForm.createRegistry] 造一份，再用
 * [cn.changjiahong.composexmlform.ComposeXmlFormProvider] 供给。
 */
val LocalEngineRegistry: ProvidableCompositionLocal<EngineRegistry> = staticCompositionLocalOf {
    ComposeXmlForm.registry
}
