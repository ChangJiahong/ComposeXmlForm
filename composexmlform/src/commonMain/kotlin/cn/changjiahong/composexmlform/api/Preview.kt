package cn.changjiahong.composexmlform.api

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
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
 * 但它依赖几个 CompositionLocal（env / 方法链 / 渲染器表），
 * 单独调用会直接抛「LocalEnv 没有被提供」。这个函数就是那些默认值的包装：
 *
 * ```kotlin
 * @Preview
 * @Composable
 * fun MyViewPreview() {
 *     val data = rememberData(mapOf("name" to "张三"))
 *     val tree = remember { ComposeXmlForm.parse(xml, data).root!! }   // 解析一次
 *     preview(tree, data = data)
 * }
 * ```
 *
 * **与 [DynamicLayout] 的分工**：这里是「一棵已经建好的树」的渲染入口，
 * 所以不解析、不校验、不管生命周期；需要 XML 的场合一律用 [DynamicLayout]。
 *
 * 默认值都「空但可用」：空数据根、空动作表、空扩展链。写绑定的节点（`{{name}}`）
 * 也不会崩，只是读到空。
 *
 * @param onDiagnostics 渲染期诊断的**累积全量**：首帧必定回调一次（可能为空表），
 *   之后只在出现新诊断时再回调。渲染期诊断会随每次重组重复产生，这里按
 *   `code + tag + attribute + message` 去重，所以同一处写错不会反复刷屏。
 *   与 [DynamicLayout] 的同名参数分工一致：那边拿的是**解析期**一次性的诊断表。
 * @param diagnosticSink 逐条出口，需要自己决定去重策略时用它；两个可以同时给。
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
    onDiagnostics: (List<Diagnostic>) -> Unit = {},
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
) {
    // data 为 null 时造一个空根：必须 remember，否则每次组合都是新对象，
    // 绑定订阅会整体失效（等价于每次重组都「换了一份数据源」）
    val resolved = data ?: remember { dataOf() }
    val env = rememberEnv(resolved, config, globals, actions)

    // 换树就换收集器：上一棵树的诊断不该算到这一棵头上
    val collector = remember(node) { DiagnosticLog() }
    val sink = remember(collector, diagnosticSink) {
        DiagnosticSink { diagnostic ->
            diagnosticSink.report(diagnostic)
            collector.record(diagnostic)
        }
    }
    val report = onDiagnostics

    CompositionLocalProvider(
        LocalEnv provides env,
        LocalMethods provides methods,
        LocalRenderers provides registry.renderers,
        LocalDiagnostics provides sink,
    ) {
        Box(modifier) { RenderNode(node) }
    }

    // 顺序是有语义的：SideEffect 按注册顺序执行，而子树的诊断是在渲染时经 sink 记录的。
    // 这个 SideEffect 必须**排在 provider 之后**，否则首帧汇总时看到的还是一张空表，
    // 而那一帧之后没有重组，诊断就永远到不了宿主。
    SideEffect { collector.flush(report) }
}

/**
 * 去重后的诊断累积表。
 *
 * `Diagnostic` 没有重写 `equals`（它带 `Span` 与自由文本，结构相等没有意义），
 * 所以键自己拼：同类同位置的重复上报才算同一条。
 *
 * [flushed] 记的是**上次交给宿主时的条数**而不是集合本身 —— 只比较条数就够了：
 * 这个表只增不减，条数不变等于内容不变。
 */
private class DiagnosticLog {

    private val seen = LinkedHashMap<String, Diagnostic>()

    private var flushed = -1

    fun record(diagnostic: Diagnostic) {
        seen.getOrPut(keyOf(diagnostic)) { diagnostic }
    }

    /** 有新诊断（或还从没报过）才回调；否则什么都不做。 */
    fun flush(report: (List<Diagnostic>) -> Unit) {
        if (seen.size == flushed) return
        flushed = seen.size
        report(seen.values.toList())
    }

    private fun keyOf(diagnostic: Diagnostic): String = buildString {
        append(diagnostic.code).append('|')
            .append(diagnostic.tag).append('|')
            .append(diagnostic.attribute).append('|')
            .append(diagnostic.message)
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
