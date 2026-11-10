package cn.changjiahong.composexmlform.api

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.eval.MethodExtensions
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.render.DiagnosticSink
import cn.changjiahong.composexmlform.engine.render.LocalDiagnostics
import cn.changjiahong.composexmlform.engine.render.LocalEnv
import cn.changjiahong.composexmlform.engine.render.LocalMethods
import cn.changjiahong.composexmlform.engine.render.LocalRenderers
import cn.changjiahong.composexmlform.engine.render.RenderNode
import cn.changjiahong.composexmlform.engine.value.DataObject

/**
 * 直接渲染一棵**节点树**，不经过 XML。
 *
 * 存在的理由：节点树本来就与 XML 无关（`TextNode` 可以纯代码构造），
 * 但 [RenderNode] 依赖几个 CompositionLocal（env / 方法链 / 渲染器表），
 * 单独调用会直接抛「LocalEnv 没有被提供」。这个函数就是那些默认值的包装：
 *
 * ```kotlin
 * @Preview
 * @Composable
 * fun RatingBarPreview() = preview(RatingBarNode(...))
 *
 * // 渲染层单元测试
 * runComposeUiTest { setContent { preview(node) } }
 * ```
 *
 * **与 [DynamicLayout] 的分工**：这里是「一棵已经建好的树」的渲染入口，
 * 所以不解析、不校验、不管生命周期；需要 XML 的场合一律用 [DynamicLayout]。
 *
 * 默认值都「空但可用」：空数据根、空动作表、空扩展链。写绑定的节点（`{{name}}`）
 * 也不会崩，只是读到空。
 */
@Composable
fun preview(
    node: Node,
    data: DataObject? = null,
    config: Map<String, Any?> = emptyMap(),
    globals: Globals = Globals.Empty,
    actions: Actions = Actions.Empty,
    methods: MethodExtensions = MethodExtensions.Empty,
    registry: EngineRegistry = LocalEngineRegistry.current,
    modifier: Modifier = Modifier,
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
) {
    // data 为 null 时造一个空根：必须 remember，否则每次组合都是新对象，
    // 绑定订阅会整体失效（等价于每次重组都「换了一份数据源」）
    val resolved = data ?: remember { dataOf() }
    val env = rememberEnv(resolved, config, globals, actions)

    CompositionLocalProvider(
        LocalEnv provides env,
        LocalMethods provides methods,
        LocalRenderers provides registry.renderers,
        LocalDiagnostics provides diagnosticSink,
    ) {
        Box(modifier) { RenderNode(node) }
    }
}

/**
 * 建一个「只有 env」的现场。
 *
 * 动作在这里也是增量装入的：`actions { }` 每次重组都是新实例，写进快照状态会重组循环。
 */
@Composable
internal fun rememberEnv(
    data: DataObject,
    config: Map<String, Any?>,
    globals: Globals,
    actions: Actions,
): Env {
    val configObject = remember(config) { DataObject.frozen(config) }
    val actionObject = remember(data, configObject, globals) { ActionObject() }
    val env = remember(data, configObject, globals, actionObject) {
        buildEnv(data, configObject, globals, actionObject).also { actionObject.attach(it, data) }
    }
    SideEffect { actionObject.register(actions) }
    return env
}
