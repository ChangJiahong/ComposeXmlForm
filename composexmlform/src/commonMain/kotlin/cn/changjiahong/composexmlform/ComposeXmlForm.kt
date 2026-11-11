package cn.changjiahong.composexmlform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import cn.changjiahong.composexmlform.api.EngineRegistry
import cn.changjiahong.composexmlform.api.Globals
import cn.changjiahong.composexmlform.api.LocalEngineRegistry
import cn.changjiahong.composexmlform.components.DefaultComponents
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.eval.MethodExtensions
import cn.changjiahong.composexmlform.engine.parse.ParseResult
import cn.changjiahong.composexmlform.engine.parse.ScriptMode
import cn.changjiahong.composexmlform.engine.parse.parse as parseXml
import cn.changjiahong.composexmlform.engine.value.DataObject

/**
 * 引擎门面。
 *
 * ```
 * val registry = ComposeXmlForm.createRegistry {
 *     component("RatingBar", create = ::RatingBarNode, render = ::RatingBar)   // 追加
 *     component("Text", replace = true, create = ::MyTextNode, render = ::MyText)  // 覆盖内置
 * }
 *
 * ComposeXmlFormProvider(registry) {       // 根上供给一次
 *     DynamicLayout(xml = xml, data = data)
 * }
 * ```
 */
object ComposeXmlForm {

    /** 全局共享注册表：内置组件 + 之后动态注册的组件。 */
    val registry: EngineRegistry by lazy { createRegistry() }

    /**
     * 新建注册表。
     *
     * @param includeDefaults 是否装内置组件（`false` 用于「只要骨架」的场景与测试）
     * @param configure 组装 DSL。**在内置组件之后执行**，所以可以直接
     *   `component(tag, replace = true, …)` 覆盖内置，或 `component(tag, …)` 追加。
     */
    fun createRegistry(
        includeDefaults: Boolean = true,
        configure: EngineRegistry.() -> Unit = {},
    ): EngineRegistry = EngineRegistry().apply {
        if (includeDefaults) install(DefaultComponents)
        configure()
    }

    /**
     * 只解析不渲染（IDE 预览、调试台、单元测试）。
     *
     * 需要完整的全局对象表才能判 `{{dta.x}}` 这类拼写错，所以这里也要一套 env ——
     * 与 `DynamicLayout` 同一套构造规则，只是没有界面。
     */
    fun parse(
        xml: String,
        data: DataObject,
        config: Map<String, Any?> = emptyMap(),
        globals: Globals = Globals.Empty,
        scriptMode: ScriptMode = ScriptMode.ExpressionsOnly,
        registry: EngineRegistry = this.registry,
    ): ParseResult = parseXml(xml, buildParseEnv(data, config, globals).schema, registry.types, scriptMode)
}

/**
 * 在根部供给注册表：下面所有 `DynamicLayout` 默认用它，不必逐页传参。
 *
 * 不新增 CompositionLocal —— [LocalEngineRegistry] 已经存在，只是把它的默认值
 * 指向 [ComposeXmlForm.registry]。`DynamicLayout` 内部还会再 provides 同一个值，幂等。
 */
@Composable
fun ComposeXmlFormProvider(
    registry: EngineRegistry = ComposeXmlForm.registry,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalEngineRegistry provides registry, content = content)

/**
 * 解析用的 env：只需要全局对象表（schema 从对象推导），不需要动作绑定。
 *
 * 单独造一份而不是复用 `LayoutRuntime`：解析期不该依赖「有没有注册过动作」，
 * 否则同一份 XML 在有/无 action 时诊断会不同。
 */
private fun buildParseEnv(
    data: DataObject,
    config: Map<String, Any?>,
    globals: Globals,
): Env {
    val objects = LinkedHashMap<String, DataObject>(4 + globals.objects.size)
    objects["data"] = data
    objects["config"] = DataObject.frozen(config)
    objects["action"] = DataObject.frozen(emptyMap())
    objects.putAll(globals.objects)
    return Env.root(objects)
}
