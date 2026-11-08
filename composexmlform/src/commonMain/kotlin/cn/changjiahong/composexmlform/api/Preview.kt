package cn.changjiahong.composexmlform.api

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.renderer.LocalActionRegistry
import cn.changjiahong.composexmlform.renderer.LocalBindableData
import cn.changjiahong.composexmlform.renderer.LocalEngineRegistry
import cn.changjiahong.composexmlform.renderer.LocalFormState
import cn.changjiahong.composexmlform.renderer.LocalStoreOwner
import cn.changjiahong.composexmlform.renderer.RenderNode
import cn.changjiahong.composexmlform.runtime.ActionRegistry
import cn.changjiahong.composexmlform.store.RootStore
import cn.changjiahong.composexmlform.store.StoreOwner
import cn.changjiahong.composexmlform.validation.FormState

/**
 * 直接渲染一棵**节点树**，不经过 XML。
 *
 * 存在的理由：节点树早就能够脱离 XML 构造（`TextNode(Attrs.literal("value" to "你好"))`），
 * 但 [RenderNode] 依赖 4 个 CompositionLocal（数据根 / 动作表 / 注册表 / 表单状态），
 * 单独调用会直接抛「没有提供 StoreOwner」。这类工具函数就是那 4 个默认值的包装：
 *
 * ```kotlin
 * // @Preview 里做一个组件的样子（不用写一份只有一行的 XML）
 * @Preview
 * @Composable
 * fun RatingBarPreview() = preview(RatingBarNode(Attrs.literal("value" to 3, "max" to 5)))
 *
 * // 渲染层的单元测试：断言「只重跑读它的那个渲染器」
 * val test = runComposeUiTest { setContent { preview(TextNode(...)) } }
 * ```
 *
 * **与 [DynamicLayout] 的分工**：这里是「一棵已经建好的树」的渲染入口，所以
 * 不解析、不校验动作、不管生命周期；需要 XML 的场合一律用 [DynamicLayout]。
 *
 * 默认值都是「空的但可用」：空数据根、空动作表、空表单状态。因此
 * `preview(TextNode(...))` 不写任何参数就能跑 —— 写绑定的节点（`{{name}}`）
 * 也不会崩，只是读到 null。
 */
@Composable
fun preview(
    node: Node,
    data: BindableData? = null,
    actions: Actions = Actions.Empty,
    form: FormState? = null,
    modifier: Modifier = Modifier,
    registry: EngineRegistry = LocalEngineRegistry.current,
) {
    // data 为 null 时造一个空数据根：必须 remember，否则每次组合都是一份新 Store，
    // 绑定订阅会整体失效（等价于每次重组都「换了一份数据源」）。
    val resolved = data ?: remember { BindableData.of(emptyMap()) }
    val owner = remember(resolved) {
        StoreOwner(RootStore(resolved.map, resolved.changeListener), parent = null)
    }
    val actionRegistry = remember { ActionRegistry() }
    // 与 DynamicLayout 同一套语义：动作表增量注册，界面已渲染后再加也能点。
    SideEffect { actionRegistry.registerAll(actions) }

    CompositionLocalProvider(
        LocalStoreOwner provides owner,
        LocalEngineRegistry provides registry,
        LocalActionRegistry provides actionRegistry,
        LocalBindableData provides resolved,
        LocalFormState provides form,
    ) {
        Box(modifier) {
            RenderNode(node)
        }
    }
}
