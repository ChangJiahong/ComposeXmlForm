package cn.changjiahong.composexmlui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.runtime.ActionRegistry
import cn.changjiahong.composexmlui.store.StoreOwner

/**
 * 渲染器基类：一种节点对应一个渲染器。
 *
 * 泛型参数保证「节点类型」与「渲染逻辑」在编译期绑定，注册表再做一次运行时校验。
 * 子类只实现 [Render]，需要往下画时调 [RenderChild] —— 和 Android 里
 * `View` / `ViewGroup.dispatchDraw` 的分工一致：**容器自己决定画几个子节点、
 * 按什么顺序画、带什么作用域**，引擎不替它遍历。
 *
 * 为什么必须是基类而不是接口：`Foreach` 要把同一个子节点渲染 N 次（每行不同作用域），
 * `If` 只渲染一支 —— 这说明「递归」不是一个统一的遍历，而是各个容器自己的策略。
 * 把入口做成基类的受保护方法，容器渲染器因此不必再引一个全局函数。
 */
abstract class NodeRenderer<T : Node> {

    @Composable
    abstract fun Render(node: T)

    /** 渲染一个子节点：按节点类型查渲染器并交给它。 */
    @Composable
    protected fun RenderChild(node: Node) {
        RenderNode(node)
    }
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

/** 统一渲染入口：根节点和任意需要越过渲染器基类的地方用它。 */
@Composable
fun RenderNode(node: Node) {
    LocalEngineRegistry.current.render(node)
}
