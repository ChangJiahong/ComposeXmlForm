package cn.changjiahong.composexmlform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import cn.changjiahong.composexmlform.api.ComponentModule
import cn.changjiahong.composexmlform.api.install
import cn.changjiahong.composexmlform.components.BoxNodeRenderer
import cn.changjiahong.composexmlform.components.ButtonNodeRenderer
import cn.changjiahong.composexmlform.components.ColumnNodeRenderer
import cn.changjiahong.composexmlform.components.RowNodeRenderer
import cn.changjiahong.composexmlform.components.SelectNodeRenderer
import cn.changjiahong.composexmlform.components.TextFieldNodeRenderer
import cn.changjiahong.composexmlform.components.TextNodeRenderer
import cn.changjiahong.composexmlform.components.UnknownNodeRenderer
import cn.changjiahong.composexmlform.controls.ForeachNodeRenderer
import cn.changjiahong.composexmlform.controls.IfNodeRenderer
import cn.changjiahong.composexmlform.node.BoxNode
import cn.changjiahong.composexmlform.node.ButtonNode
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.ForeachNode
import cn.changjiahong.composexmlform.node.IfNode
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.RowNode
import cn.changjiahong.composexmlform.node.SelectNode
import cn.changjiahong.composexmlform.node.TextFieldNode
import cn.changjiahong.composexmlform.node.TextNode
import cn.changjiahong.composexmlform.node.UnknownNode
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.renderer.LocalEngineRegistry

/**
 * 引擎门面。
 *
 * 使用方式：
 * ```
 * val registry = ComposeXmlForm.createRegistry {
 *     install(DemoComponents)
 *     leaf("Chip", ::ChipNode) { node -> Chip(node.label.get("")) }
 * }
 *
 * ComposeXmlFormProvider(registry) {          // 根上供给一次
 *     DynamicLayout(xml = xml, data = data) // 页面不再传 registry
 * }
 * ```
 */
object ComposeXmlForm {

    /** 全局共享注册表：内置组件 + 运行时动态注册的组件。 */
    val registry: EngineRegistry by lazy { createRegistry() }

    /**
     * 新建注册表。
     *
     * @param includeDefaults 是否装内置组件（`false` 用于「只要骨架」的极端场景与测试）
     * @param configure 组装 DSL。**在内置组件之后执行**，所以可以直接
     *   `replace("Text", ...)` 覆盖内置，或 `install(DemoComponents)` 追加。
     */
    fun createRegistry(
        includeDefaults: Boolean = true,
        configure: EngineRegistry.() -> Unit = {},
    ): EngineRegistry = EngineRegistry().apply {
        if (includeDefaults) install(DefaultComponents)
        configure()
    }

    /** 仅解析与校验，不渲染（供 IDE 预览、调试台、单元测试使用）。 */
    fun parse(
        xml: String,
        registry: EngineRegistry = this.registry,
        config: Map<String, Any?> = emptyMap(),
    ): Node = registry.parse(xml, config).root
}

/**
 * 在根部供给注册表：下面的所有 `DynamicLayout` 默认用它，不必逐页传参。
 *
 * 不新增 CompositionLocal —— [LocalEngineRegistry] 已经存在，只是把它的默认值
 * 从「抛错」改成 [ComposeXmlForm.registry]。`DynamicLayout` 内部还会再 provides 同一个值，
 * 幂等。
 */
@Composable
fun ComposeXmlFormProvider(
    registry: EngineRegistry = ComposeXmlForm.registry,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalEngineRegistry provides registry, content = content)

/**
 * 内置组件注册。
 *
 * 内置组件走的是和第三方扩展**完全相同**的注册路径，没有任何特权：
 * 覆盖 `"Text"` 的标签注册就能换掉内置节点，`repaint<TextNode>(...)` 就能换掉内置画法，
 * 两者互不影响。
 *
 * 属性白名单由节点实际读取了哪些属性自动推导（`Attrs.assertAllConsumed`），
 * 因此新增属性只需要在节点构造参数里加一行，不会有第二处需要同步。
 */
val DefaultComponents: ComponentModule = ComponentModule { registry ->

    // ---------------- 叶子组件 ----------------

    registry.leaf("Text", ::TextNode, TextNodeRenderer())
    registry.leaf("Button", ::ButtonNode, ButtonNodeRenderer())
    registry.leaf("TextField", ::TextFieldNode, TextFieldNodeRenderer())
    registry.leaf("Select", ::SelectNode, SelectNodeRenderer())

    // ---------------- 布局组件 ----------------

    registry.container("Column", ::ColumnNode, ColumnNodeRenderer())
    registry.container("Row", ::RowNode, RowNodeRenderer())
    registry.container("Box", ::BoxNode, BoxNodeRenderer())

    // ---------------- 控制节点 ----------------

    registry.container("If", ::IfNode, IfNodeRenderer())
    registry.container("Foreach", ::ForeachNode, ForeachNodeRenderer())

    // Tolerant 模式下的未知标签占位。只注册**渲染器**，不注册标签：
    // UnknownNode 由解析器直接构造，给它一个标签反而会和用户真正的 `<Unknown/>` 撞名。
    registry.repaint<UnknownNode>(UnknownNodeRenderer())
}
