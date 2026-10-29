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
 * 动态界面入口：提供 XML、初始数据、只读配置与动作函数即可渲染界面。
 *
 * 生命周期约定：
 * - 运行时以 (xml, config, registry) 为键记忆，重组不会重建运行时，也不会丢失用户输入。
 * - `data` 只在首次创建运行时生效；后续修改数据请使用 [DynamicLayoutController]，
 *   避免「每次重组传入新 Map 就把用户输入重置」的问题。
 * - 如果确实需要用新的 data 整体重建界面，请更换 `xml` 或 `config`，或在外层用 key() 包裹。
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
    val runtime = remember(xml, config, registry) {
        DynamicLayoutRuntime(
            xml = xml,
            data = data,
            config = config,
            actions = actions,
            registry = registry,
        )
    }

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
