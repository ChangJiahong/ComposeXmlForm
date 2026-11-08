package cn.changjiahong.composexmlform.runtime

import cn.changjiahong.composexmlform.api.ActionHandler
import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.AttrSite

/**
 * 动作注册表：运行时可变表，`name → handler`。
 *
 * 与 [Actions] 的分工：[Actions] 是**构造入参**（声明「这次界面有哪些动作」），
 * 这里是运行期实际生效的那份，`controller` 侧可以增删。
 *
 * 只在内部按 `name` 存取；调用方拿 handler 时**必须带 [AttrSite]**，
 * 这样「引用了未注册的函数」才有标签 / 属性名 / 行号，而不是一句「未注册的动作」。
 */
class ActionRegistry {

    private val handlers = LinkedHashMap<String, ActionHandler>()

    fun register(name: String, handler: ActionHandler) {
        handlers[name] = handler
    }

    /** 增量注册：不删除已有的（界面已渲染，回溯移除会打断它）。 */
    fun registerAll(actions: Actions) {
        handlers.putAll(actions.handlers)
    }

    fun replaceAll(actions: Actions) {
        handlers.clear()
        handlers.putAll(actions.handlers)
    }

    fun unregister(name: String) {
        handlers.remove(name)
    }

    fun has(name: String): Boolean = handlers.containsKey(name)

    fun names(): Set<String> = handlers.keys.toSet()

    /**
     * 取 handler。[site] 只用于报错定位 —— 解析期已经校验过「注册了没有」，
     * 这里兜的是「运行期被移除了」这种少数情况。
     */
    fun handler(name: String, site: AttrSite? = null): ActionHandler {
        handlers[name]?.let { return it }
        throw XmlUiException(
            "未注册的动作 $name()。请通过 actions { on(\"$name\") { ... } } 注册",
            tag = site?.tag,
            attribute = site?.attribute,
            line = site?.line ?: 0,
            code = Code.UnregisteredAction,
        )
    }

    fun clear() {
        handlers.clear()
    }
}
