package cn.changjiahong.composexmlform.runtime

import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.ActionUsage
import cn.changjiahong.composexmlform.node.ValidationPlan
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.parser.ParseResult
import cn.changjiahong.composexmlform.store.RootStore
import cn.changjiahong.composexmlform.store.StoreOwner

/**
 * 单个动态界面实例的运行时。
 *
 * - [layout]：解析结果（节点树 + 诊断 + 动作引用），每次构造解析一次，随运行时生命周期回收
 * - [data]：业务数据门面（由调用方持有，界面销毁重建后数据仍在）
 * - [rootOwner]：业务数据与作用域（与 [data] 共享同一个可观察数据根）
 * - [actionRegistry]：事件函数
 * - [registry]：组件注册表（通常由引擎共享）
 */
class DynamicLayoutRuntime(
    val xml: String,
    val data: BindableData,
    config: Map<String, Any?>,
    actions: Actions,
    val registry: EngineRegistry,
) {

    /**
     * 节点树每次构造时解析一次，**不做跨实例缓存**。
     *
     * 实测一段 31 行 / 30 节点的表单 XML 解析约 90µs（不到一帧的 1%），
     * 而 `DynamicLayout` 里 `remember(xml, config, registry, data)` 已经保证
     * 数据变化不重建运行时，所以「解析结果复用」在这套生命周期里换不来可感知收益，
     * 却要付出全局可变状态 + 缓存失效判定的代价。真需要共享时由调用方
     * 自己 `remember` 住 [ParseResult] 即可。
     *
     * `config` 只在这一行出现：解析结束它就没了，运行时不存在 config 概念。
     */
    val layout: ParseResult = registry.parse(xml, config)

    /** 解析期收集的字段校验规则（R11）；`FormState.attach(...)` 消费它。 */
    val validationPlan: ValidationPlan get() = layout.validationPlan

    /** 数据根与变更回调共享同一个 `BindableData`：引擎写回因此能被 `changes()` 看见。 */
    val rootOwner: StoreOwner = StoreOwner(RootStore(data.map, data.changeListener), parent = null)

    val actionRegistry: ActionRegistry = ActionRegistry()

    init {
        actionRegistry.replaceAll(actions)
        validateActions(layout.actionUsages)
    }

    fun dispose() {
        actionRegistry.clear()
    }

    /**
     * 构造期校验「引用的动作都注册了」。
     *
     * 数据来源是**解析期收集的** [ParseResult.actionUsages]，不是遍历节点树：
     * 树里只有声明了 `ValAttr<ActionRef>` 字段的节点会被看到，而且拿不到「属性叫什么」。
     * 用收集清单后，`<If>` / `<Foreach>` 各分支里的引用同样被覆盖，报错也带真实标签与行号。
     */
    private fun validateActions(usages: List<ActionUsage>) {
        usages.firstOrNull { !actionRegistry.has(it.ref.name) }?.let { usage ->
            throw XmlUiException(
                "action 引用了未注册的函数 ${usage.ref.raw}。" +
                    "请通过 actions { on(\"${usage.ref.name}\") { ... } } 注册",
                tag = usage.site.tag,
                attribute = usage.site.attribute,
                line = usage.site.line,
                code = Code.UnregisteredAction,
            )
        }
    }
}
