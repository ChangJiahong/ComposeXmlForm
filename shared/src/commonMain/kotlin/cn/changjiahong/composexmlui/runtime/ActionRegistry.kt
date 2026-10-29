package cn.changjiahong.composexmlui.runtime

import cn.changjiahong.composexmlui.core.XmlUiException

/**
 * 动作注册表：只保存「无参函数」，XML 中只能引用函数名。
 * 不在 XML 里执行任意代码，也不支持传参。
 */
class ActionRegistry {

    private val actions = LinkedHashMap<String, () -> Unit>()

    fun register(name: String, action: () -> Unit) {
        actions[name] = action
    }

    fun registerAll(source: Map<String, () -> Unit>) {
        actions.putAll(source)
    }

    fun replaceAll(source: Map<String, () -> Unit>) {
        actions.clear()
        actions.putAll(source)
    }

    fun unregister(name: String) {
        actions.remove(name)
    }

    fun has(name: String): Boolean = actions.containsKey(name)

    fun names(): Set<String> = actions.keys.toSet()

    fun invoke(name: String) {
        val action = actions[name] ?: throw XmlUiException(
            "未注册的动作: $name()。请通过 DynamicLayout 的 actions 参数或 ActionRegistry.register 注册",
        )
        action.invoke()
    }

    fun clear() {
        actions.clear()
    }
}
