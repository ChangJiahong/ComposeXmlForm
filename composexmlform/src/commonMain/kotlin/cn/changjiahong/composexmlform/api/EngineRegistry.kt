package cn.changjiahong.composexmlform.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlform.ComposeXmlForm
import cn.changjiahong.composexmlform.api.decl.Props
import cn.changjiahong.composexmlform.api.decl.Schema
import cn.changjiahong.composexmlform.api.decl.propsOf
import cn.changjiahong.composexmlform.api.decl.prototypeProps
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

    /** `tag → 节点类`，只为 [component] 的 `replace` 服务：换标签时要把旧类从渲染器表里摘掉。 */
    private val classByTag = HashMap<String, KClass<*>>()

    internal val types: NodeTypes get() = typesState.value

    internal val renderers: Renderers get() = renderersState.value

    /** 每次成功注册加一。读了就订阅。 */
    val version: Long get() = versionState.value

    val tags: Set<String> get() = types.tags

    /**
     * 注册一个组件：**属性、构造、画法一次说完**。
     *
     * ```kotlin
     * registry.component("RatingBar", create = ::RatingBarNode, render = ::RatingBar)
     * ```
     *
     * 三件事由框架补齐，宿主都不写：
     *
     * 1. **属性表**：先用空 [Props] 把节点构造一次，构造期调用的每个 `props.xxx(...)` 都记进声明表
     *    （`Attr` 对象、`create` 里逐个 `attrs.attr(...)`、构造函数参数三处重复从此消失）；
     * 2. **节点类型**：`tag` / 属性表 / 子节点规则，一次性登记；
     * 3. **渲染器**：`render` 就是个 Composable，不用实现 `NodeRenderer`、不用起类名。
     *
     * [render] 收到的是**已绑定本行现场**的节点，所以可以直接 `node.value` / `node.value = x`。
     *
     * [replace] 用于宿主覆盖已存在的标签（内置的 `Text` 之类）。默认 `false` 时重名**直接失败** ——
     * 两个 `Text` 同时存在时「谁生效」取决于注册顺序，那是最难查的一类 bug。
     */
    fun <T : Node> component(
        tag: String,
        childRule: ChildRule = ChildRule.None,
        replace: Boolean = false,
        create: (Props) -> T,
        render: @Composable (T) -> Unit,
    ): EngineRegistry {
        val schema = Schema(tag)
        val prototype = create(prototypeProps(schema))

        @Suppress("UNCHECKED_CAST")
        val nodeClass = prototype::class as KClass<T>

        val type = NodeType(
            tag = tag,
            attributes = schema.decls,
            childRule = childRule,
            create = { id, attrs, children -> create(propsOf(schema, id, attrs, children, create)) },
        )
        val renderer = NodeRenderer<T> { node -> render(node) }

        if (replace) {
            // 旧标签的节点类可能和新标签不是同一个：不摘掉的话它会永远留在渲染器表里。
            classByTag[tag]?.let { old -> renderersState.value = renderersState.value.minus(old) }
            typesState.value = typesState.value.replacing(type)
            renderersState.value = renderersState.value.replacing(nodeClass, renderer)
        } else {
            typesState.value = typesState.value.plus(type)
            renderersState.value = renderersState.value.plus(nodeClass, renderer)
        }

        classByTag[tag] = nodeClass
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
