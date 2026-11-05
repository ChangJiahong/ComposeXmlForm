package cn.changjiahong.composexmlui.api

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import cn.changjiahong.composexmlui.ComposeXmlUi
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.renderer.LocalActionRegistry
import cn.changjiahong.composexmlui.renderer.LocalEngineRegistry
import cn.changjiahong.composexmlui.renderer.LocalStoreOwner
import cn.changjiahong.composexmlui.renderer.RenderNode
import cn.changjiahong.composexmlui.runtime.DynamicLayoutRuntime

/**
 * 动态界面入口（推荐）：业务数据由调用方持有，界面是它的一个视图。
 *
 * 生命周期约定：
 * - 运行时以 `(xml, config, registry, data)` 为键记忆：`data` 实例不变时，数据更新
 *   走 [BindableData] 的方法，界面不会重建、用户输入不丢。
 * - 换一个 [BindableData] 实例即视为「换了一份数据源」，运行时会重建（符合直觉）。
 * - `config` 仍是只读配置，变化时重建；`actions` 以首次创建为准，后续新增即时生效。
 */
@Composable
fun DynamicLayout(
    xml: String,
    data: BindableData,
    config: Map<String, Any?> = emptyMap(),
    actions: Map<String, () -> Unit> = emptyMap(),
    modifier: Modifier = Modifier.fillMaxSize(),
    registry: EngineRegistry = ComposeXmlUi.registry,
    onReady: ((DynamicLayoutController) -> Unit)? = null,
) {
    val runtime = remember(xml, config, registry, data) {
        DynamicLayoutRuntime(
            xml = xml,
            data = data,
            config = config,
            actions = actions,
            registry = registry,
        )
    }
    DynamicLayoutContent(runtime, actions, modifier, registry, onReady)
}

/**
 * 便捷重载：直接传 Map。
 *
 * 注意：这里的 `data` **只在首次创建时生效**（内部包一层 [BindableData] 后不再跟随参数变化），
 * 这是刻意的 —— 否则调用方每次重组传入新 Map 会把用户输入重置。
 * 需要在界面存活期间更新数据，请改用 [BindableData] 重载并由自己持有实例。
 */
@Composable
fun DynamicLayout(
    xml: String,
    data: Map<String, Any?> = emptyMap(),
    config: Map<String, Any?> = emptyMap(),
    actions: Map<String, () -> Unit> = emptyMap(),
    modifier: Modifier = Modifier.fillMaxSize(),
    registry: EngineRegistry = ComposeXmlUi.registry,
    onReady: ((DynamicLayoutController) -> Unit)? = null,
) {
    val bindable = remember(xml, config, registry) { BindableData(data) }
    DynamicLayout(
        xml = xml,
        data = bindable,
        config = config,
        actions = actions,
        modifier = modifier,
        registry = registry,
        onReady = onReady,
    )
}

@Composable
private fun DynamicLayoutContent(
    runtime: DynamicLayoutRuntime,
    actions: Map<String, () -> Unit>,
    modifier: Modifier,
    registry: EngineRegistry,
    onReady: ((DynamicLayoutController) -> Unit)?,
) {
    // 动作表以首次创建为准，后续新增的动作即时生效（移除不回溯，避免打断已渲染界面）。
    SideEffect { runtime.actionRegistry.registerAll(actions) }

    DisposableEffect(runtime) {
        onDispose { runtime.dispose() }
    }

    val readyCallback = rememberUpdatedState(onReady)
    LaunchedEffect(runtime) {
        readyCallback.value?.invoke(runtime.controller)
    }

    CompositionLocalProvider(
        LocalStoreOwner provides runtime.rootOwner,
        LocalEngineRegistry provides registry,
        LocalActionRegistry provides runtime.actionRegistry,
    ) {
        Box(modifier) {
            RenderNode(runtime.layout.root)
        }
    }
}
