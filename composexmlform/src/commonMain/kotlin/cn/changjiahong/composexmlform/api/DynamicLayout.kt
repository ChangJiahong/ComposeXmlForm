package cn.changjiahong.composexmlform.api

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.renderer.LocalActionRegistry
import cn.changjiahong.composexmlform.renderer.LocalBindableData
import cn.changjiahong.composexmlform.renderer.LocalEngineRegistry
import cn.changjiahong.composexmlform.renderer.LocalFormState
import cn.changjiahong.composexmlform.renderer.LocalStoreOwner
import cn.changjiahong.composexmlform.renderer.RenderNode
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import cn.changjiahong.composexmlform.validation.FormState

/**
 * 动态界面入口。业务数据由调用方持有，界面是它的一个视图。
 *
 * 数据只有一种形态：[BindableData]。在 Compose 里用 [rememberBindableData] 创建，
 * 跨页面 / ViewModel 场景用 `BindableData.of(...)`。
 *
 * 生命周期约定：
 * - 运行时以 `(xml, config, registry, data)` 为键记忆：`data` 实例不变时，数据更新
 *   走 [BindableData] 的方法，界面不会重建、用户输入不丢。
 * - 换一个 [BindableData] 实例即视为「换了一份数据源」，运行时会重建（符合直觉）。
 * - `config` 是服务器下发的只读布局资源（解析期固化），变化时重建。
 * - `actions` **不进** remember key：`actions { }` 每次重组都会新建 `Actions`，
 *   但动作表是增量注册的（`SideEffect`），所以新增的动作立刻生效，也不会重建界面。
 */
@Composable
fun DynamicLayout(
    xml: String,
    data: BindableData,
    config: Map<String, Any?> = emptyMap(),
    actions: Actions = Actions.Empty,
    modifier: Modifier = Modifier.fillMaxSize(),
    registry: EngineRegistry = LocalEngineRegistry.current,
    form: FormState? = null,
) {
    // registry.version 进 key：界面组合完之后再注册组件也要立即生效，
    // 否则 <X> 会一直报「未注册」，直到别的参数变化碰巧重建运行时。
    val runtime = remember(xml, config, registry, registry.version, data) {
        DynamicLayoutRuntime(
            xml = xml,
            data = data,
            config = config,
            actions = actions,
            registry = registry,
        )
    }
    DynamicLayoutContent(runtime, actions, modifier, registry, form)
}

@Composable
private fun DynamicLayoutContent(
    runtime: DynamicLayoutRuntime,
    actions: Actions,
    modifier: Modifier,
    registry: EngineRegistry,
    form: FormState?,
) {
    // 动作表以首次创建为准，后续新增的动作即时生效（移除不回溯，避免打断已渲染界面）。
    SideEffect { runtime.actionRegistry.registerAll(actions) }

    // 解析期收集的字段规则装进 FormState；换布局（运行时重建）就重新装一次。
    SideEffect { form?.attach(runtime.validationPlan) }

    DisposableEffect(runtime) {
        onDispose { runtime.dispose() }
    }

    CompositionLocalProvider(
        LocalStoreOwner provides runtime.rootOwner,
        LocalEngineRegistry provides registry,
        LocalActionRegistry provides runtime.actionRegistry,
        LocalBindableData provides runtime.data,
        LocalFormState provides form,
    ) {
        Box(modifier) {
            RenderNode(runtime.layout.root)
        }
    }
}
