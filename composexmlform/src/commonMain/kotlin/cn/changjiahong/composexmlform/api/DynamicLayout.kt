package cn.changjiahong.composexmlform.api

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.eval.MethodExtensions
import cn.changjiahong.composexmlform.engine.parse.ParseResult
import cn.changjiahong.composexmlform.engine.parse.ScriptMode
import cn.changjiahong.composexmlform.engine.parse.parse
import cn.changjiahong.composexmlform.engine.render.DiagnosticSink
import cn.changjiahong.composexmlform.engine.render.LocalDiagnostics
import cn.changjiahong.composexmlform.engine.render.LocalEnv
import cn.changjiahong.composexmlform.engine.render.LocalMethods
import cn.changjiahong.composexmlform.engine.render.LocalRenderers
import cn.changjiahong.composexmlform.engine.render.RenderNode
import cn.changjiahong.composexmlform.engine.value.DataObject

/**
 * 动态界面入口。业务数据由调用方持有，界面是它的一个视图。
 *
 * 生命周期约定：
 * - `(xml, data, config, globals, registry, registry.version, scriptMode)` 进 `remember` key。
 *   `data` 实例不变时，数据更新走 `data.set(...)`，界面不会重建、用户输入不丢。
 * - 换一个 `DataObject` 实例即视为「换了一份数据源」，运行时会重建。
 * - `config` 是服务器下发的只读布局资源（解析期固化），变化时重建。
 * - `actions` / `methods` **不进 key**：动作是增量装进 `action` 全局对象的，
 *   `actions { }` 每次重组都会新建实例，进 key 会导致每帧重新解析。
 *
 * 与旧 API 的差别：没有 `form` 参数。字段校验规则原本由解析期从控件属性里收集，
 * 而新引擎的解析阶段只产出树与诊断 —— 校验是**独立一层**，等 `LIST_OPS` 那一轮
 * 一起设计（`FormState` 需要一个规则来源，不该在 api 里再补一个临时口径）。
 */
@Composable
fun DynamicLayout(
    xml: String,
    data: DataObject,
    config: Map<String, Any?> = emptyMap(),
    globals: Globals = Globals.Empty,
    methods: MethodExtensions = MethodExtensions.Empty,
    actions: Actions = Actions.Empty,
    scriptMode: ScriptMode = ScriptMode.ExpressionsOnly,
    registry: EngineRegistry = LocalEngineRegistry.current,
    modifier: Modifier = Modifier.fillMaxSize(),
    /** 解析期诊断。一次解析报一次（换 xml / 数据 / 注册表才重报）。 */
    onDiagnostics: (List<Diagnostic>) -> Unit = {},
    /** 渲染期诊断：求值降级、缺渲染器。默认丢弃。 */
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
) {
    // config 是解析期固化的只读资源：冻结一次，整棵子树都不能被写
    val configObject = remember(config) { DataObject.frozen(config) }

    // registry.version 进 key：界面组合完之后再 install 一个包也要立即生效，
    // 否则 <X> 会一直报「未注册」，直到别的参数变化碰巧重建运行时
    val runtime = remember(xml, data, configObject, globals, registry, registry.version, scriptMode) {
        LayoutRuntime.build(xml, data, configObject, globals, actions, registry, scriptMode)
    }

    // actions 是增量注册（写普通 Map，不动快照状态），所以不会触发重组循环
    SideEffect { runtime.actionObject.register(actions) }

    val reported = remember(runtime) { mutableStateOf(false) }
    val report = onDiagnostics
    SideEffect {
        if (!reported.value) {
            reported.value = true
            report(runtime.result.diagnostics)
        }
    }

    CompositionLocalProvider(
        LocalEnv provides runtime.env,
        LocalMethods provides methods,
        LocalRenderers provides registry.renderers,
        LocalDiagnostics provides diagnosticSink,
    ) {
        Box(modifier) {
            runtime.result.root?.let { RenderNode(it) }
        }
    }
}

/**
 * 一次布局的运行时：env + 动作对象 + 解析结果。
 *
 * 三者必须一起建、一起丢：env 里的 `action` 就是这个 [actionObject]，
 * 而解析用到的 schema 又来自 env 的全局对象表 —— 拆开重建会让树指向旧表的 `action`。
 */
internal class LayoutRuntime(
    val env: Env,
    val actionObject: ActionObject,
    val result: ParseResult,
) {

    companion object {

        fun build(
            xml: String,
            data: DataObject,
            config: DataObject,
            globals: Globals,
            actions: Actions,
            registry: EngineRegistry,
            scriptMode: ScriptMode,
        ): LayoutRuntime {
            val actionObject = ActionObject()
            val env = buildEnv(data, config, globals, actionObject)
            actionObject.attach(env, data)
            actionObject.registerFunctions(globals.functions)
            actionObject.register(actions)

            val result = parse(xml, env.schema, registry.types, scriptMode)
            return LayoutRuntime(env, actionObject, result)
        }
    }
}

/**
 * 建根环境。
 *
 * 全局对象必须在建 env **之前**准备好（schema 的能力表是从对象自己推导的），
 * 唯一例外是 `action`：它的成员要闭包捕获 env，所以先建空壳、建完再回填。
 *
 * `data` / `config` / `action` 三个名字固定，宿主注册的同名对象会被忽略 ——
 * 那三个名字归引擎，`globals { object_("data") }` 会在构建期就被拒。
 */
internal fun buildEnv(
    data: DataObject,
    config: DataObject,
    globals: Globals,
    actionObject: ActionObject,
): Env {
    val objects = LinkedHashMap<String, DataObject>(4 + globals.objects.size)
    objects["data"] = data
    objects["config"] = config
    objects["action"] = actionObject
    objects.putAll(globals.objects)
    return Env.root(objects, callableNames = setOf("action"))
}
