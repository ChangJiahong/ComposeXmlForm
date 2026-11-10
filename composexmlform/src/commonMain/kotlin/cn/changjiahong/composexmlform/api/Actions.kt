package cn.changjiahong.composexmlform.api

import cn.changjiahong.composexmlform.engine.diag.FrozenWriteException
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 动作处理器的执行现场。
 *
 * 引擎只提供 [env] / [data] 与动作名 —— **宿主能力（toast / 导航 / 权限）由调用方在
 * `actions { }` 的闭包里捕获**（`on("x") { toast("ok") }`，`toast` 是宿主作用域的函数）。
 *
 * 这是刻意的：多一个 `ActionHost` 接口，就多一层每个宿主都要实现的抽象，
 * 而闭包捕获已经够用。
 */
class ActionScope(
    val env: Env,
    val data: DataObject,
    val name: String,
)

/**
 * 一个动作处理器。**同步**。
 *
 * 旧版是 `suspend`，因为「转圈 → 请求 → 提示」需要协程作用域。新模型把这件事挪到了
 * 脚本层：挂起调用是 `await http.get(url)`，由 `execute` 的挂起外壳驱动，不需要每个宿主
 * 再实现一套 `suspend` 处理器。动作因此变成普通函数 —— 它只负责「改数据」，
 * 而改数据不需要挂起。
 *
 * 要在动作里发请求：升到 `ScriptMode.Full`，在 XML 里写
 * `onClick=@{ val r = await http.get(url); data.text = r }`。
 */
fun interface ActionHandler {

    fun run(scope: ActionScope)
}

/**
 * 一组动作处理器。构造用 [actions] DSL：
 * ```
 * actions = actions {
 *     on("submit") { data.set("submitting", true) }
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

    val isEmpty: Boolean get() = handlers.isEmpty()

    override fun toString(): String = "Actions(${handlers.keys.joinToString(", ")})"

    companion object {

        val Empty: Actions = Actions(emptyMap())

        /** 从 lambda 的 Map 构造：给「动作本来就只有一行」的场合留一条直路。 */
        fun of(handlers: Map<String, ActionScope.() -> Unit>): Actions = Actions(
            handlers.mapValues { (_, handler) -> ActionHandler { scope -> scope.handler() } },
        )
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
     * 名字必须是合法标识符（XML 里写成 `@{ action.submit() }`），并且不能重复注册 ——
     * 重复时是**构建期报错**，不是静默覆盖：`on("submit")` 写两遍几乎总是复制粘贴的残留。
     */
    fun on(name: String, handler: ActionScope.() -> Unit) {
        requireIdentifier(name, "动作名")
        require(name !in handlers) {
            "动作『$name』重复注册。合并公共动作请用 Actions 的 `+`，不要让同一个名字出现两次"
        }
        handlers[name] = ActionHandler { scope -> scope.handler() }
    }

    internal fun build(): Actions = Actions(handlers.toMap())
}

/**
 * `action` 全局对象。
 *
 * 它不是普通的 [DataObject]：成员通过 [register] 增量装入，**不进快照状态**。
 * 原因很实际 —— `actions { }` 每次重组都会产生一份新的 `Actions`，
 * 如果注册写的是快照状态，就会出现「写状态 → 重组 → 再写」的死循环。
 * 这张表是普通 Map，读它不订阅，注册也不触发重组。
 *
 * 代价：**渲染之后再注册的动作，已经渲染出来的按钮看不到**（它拿的是注册那一刻的函数）。
 * 这与旧实现一致，也是合理边界 —— 换布局才该换动作。
 */
internal class ActionObject : DataObject() {

    private val members = LinkedHashMap<String, Function>()

    private var scope: ActionScope? = null

    /** 成员通过 `register` 改，不通过 `setMember`，所以在数据层看是只读的。 */
    override val frozen: Boolean get() = true

    /** 建完 env 再回填：`ActionScope` 需要 `env`，而 `env` 又需要本对象做 `action` 全局。 */
    fun attach(env: Env, data: DataObject) {
        scope = ActionScope(env, data, "action")
    }

    /** 装入一组宿主动作。同名覆盖（后注册的赢）。 */
    fun register(actions: Actions) {
        val current = scope ?: return
        for ((name, handler) in actions.handlers) {
            members[name] = Function { _, _ ->
                handler.run(ActionScope(current.env, current.data, name))
                null
            }
        }
    }

    /** 装入全局函数（`globals { fn("toast") { … } }`）。 */
    fun registerFunctions(functions: Map<String, Function>) {
        for ((name, fn) in functions) members[name] = fn
    }

    override fun member(name: String): Value = members[name] ?: Absent

    override fun setMember(name: String, value: Value) {
        throw FrozenWriteException("action.$name")
    }

    override fun removeMember(name: String): Boolean = false

    override fun keys(): List<String> = members.keys.toList()

    /** 函数不是数据 —— 与 `DataObject.snapshot` 的约定一致。 */
    override fun snapshot(): Map<String, Value> = emptyMap()

    override fun toString(): String = "ActionObject(${members.keys.joinToString()})"
}
