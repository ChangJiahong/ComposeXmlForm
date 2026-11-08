package cn.changjiahong.composexmlform.api

import cn.changjiahong.composexmlform.binding.BindingParser
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.XmlUiException

/**
 * 动作处理器：**可以挂起**，并且拿得到数据。
 *
 * 为什么不是 `() -> Unit`：
 * - 「点提交 → 转圈 → 请求 → 失败提示 → 恢复」需要一个协程作用域；
 *   用同步 lambda 表达不了，宿主只能在 `DynamicLayout` 外面自己套一层，
 *   `data` 还得靠闭包捕获。
 * - 抛异常能就地收住并上报，不会从 `onClick` 冒到 Compose 把整页带崩。
 *
 * 写成 [ActionScope] 的接收者，是为了让 handler 里能直接写 `data.set(...)`
 * 而不是 `scope.data.set(...)`。
 */
fun interface ActionHandler {
    suspend fun run(scope: ActionScope)
}

/**
 * handler 的执行上下文。引擎只提供 [data] 与协程 —— **宿主能力（toast / 导航 / 权限）
 * 由调用方在 `actions { }` 的闭包里捕获**（`on("x") { toast("ok") }`，`toast` 是宿主作用域的函数）。
 *
 * 这是刻意的：多一个 `ActionHost` 接口，就多一层每个宿主都要实现的抽象，
 * 而闭包捕获已经够用。
 */
class ActionScope(
    /** 本次界面实例的业务数据门面。 */
    val data: BindableData,

    /** 动作名，用于日志与错误信息。 */
    val name: String,
)

/**
 * 一组动作处理器。构造用 [actions] DSL：
 * ```
 * actions = actions {
 *     on("submit") { data.set("submitting", true); save() }
 *     on("cancel") { data.set("open", false) }
 * }
 * ```
 *
 * 与 `Map<String, () -> Unit>` 的差别不在形状，在**校验**：名字非法、重复注册都是构建期报错，
 * 而 Map 字面量会静默覆盖前一个。
 */
class Actions internal constructor(internal val handlers: Map<String, ActionHandler>) {

    /** 组合：公共动作组 + 本页动作。同名的后者覆盖前者（这是显式拼装，不算手误）。 */
    operator fun plus(other: Actions): Actions = Actions(handlers + other.handlers)

    val names: Set<String> get() = handlers.keys

    override fun toString(): String = "Actions(${handlers.keys.joinToString(", ")})"

    companion object {

        val Empty: Actions = Actions(emptyMap())

        /** 从同步 lambda 的 Map 构造：给「动作本来就是同步的」这种常见情况留一条直路。 */
        fun of(handlers: Map<String, () -> Unit>): Actions =
            Actions(handlers.mapValues { (_, handler) -> ActionHandler { handler() } })

        /** 从已包装的 handler 构造（异步动作、需要自己组合时用）。 */
        fun ofHandlers(handlers: Map<String, ActionHandler>): Actions = Actions(handlers)
    }
}

/** 构造一组动作。 */
fun actions(block: ActionsBuilder.() -> Unit): Actions = ActionsBuilder().apply(block).build()

/** DSL 标记：防止嵌套的 `actions { }` 里的 `on` 落到外层作用域。 */
@DslMarker
annotation class ActionsDsl

@ActionsDsl
class ActionsBuilder internal constructor() {

    private val handlers = LinkedHashMap<String, ActionHandler>()

    /**
     * 注册一个动作。
     *
     * 名字必须是合法标识符（XML 里写成 `action="submit()"`），并且不能重复注册 ——
     * 重复时是**构建期报错**，不是静默覆盖：`on("submit")` 写两遍几乎总是复制粘贴的残留。
     */
    fun on(name: String, handler: suspend ActionScope.() -> Unit) {
        if (!BindingParser.isIdentifier(name)) {
            throw XmlUiException(
                "动作名 '$name' 非法，只能包含字母、数字与下划线且不能以数字开头",
                code = Code.UnregisteredAction,
            )
        }
        if (handlers.containsKey(name)) {
            throw XmlUiException(
                "动作 '$name' 重复注册。合并公共动作请用 Actions 的 `+`，不要让同一个名字出现两次",
                code = Code.UnregisteredAction,
            )
        }
        handlers[name] = ActionHandler { scope -> scope.handler() }
    }

    internal fun build(): Actions = Actions(handlers.toMap())
}
