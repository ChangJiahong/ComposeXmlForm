package cn.changjiahong.composexmlui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.runtime.ActionRegistry
import cn.changjiahong.composexmlui.store.StoreOwner

/**
 * 渲染器统一接口：一种节点对应一个渲染器。
 * 泛型参数保证「节点类型」与「渲染逻辑」在编译期绑定，注册表再做一次运行时校验。
 */
interface NodeRenderer<T : Node> {
    @Composable
    fun Render(node: T)
}

/** 当前作用域。CompositionLocal 只传递 Owner 引用，不给 Map 注入响应式能力。 */
val LocalStoreOwner = staticCompositionLocalOf<StoreOwner> {
    throw XmlUiException("LocalStoreOwner 未提供：节点必须在 DynamicLayout 内渲染")
}

val LocalActionRegistry = staticCompositionLocalOf<ActionRegistry> {
    throw XmlUiException("LocalActionRegistry 未提供：节点必须在 DynamicLayout 内渲染")
}

val LocalEngineRegistry = staticCompositionLocalOf<EngineRegistry> {
    throw XmlUiException("LocalEngineRegistry 未提供：节点必须在 DynamicLayout 内渲染")
}

/** 统一渲染入口：所有容器 / 自定义组件都用它渲染子节点。 */
@Composable
fun RenderNode(node: Node) {
    LocalEngineRegistry.current.render(node)
}
