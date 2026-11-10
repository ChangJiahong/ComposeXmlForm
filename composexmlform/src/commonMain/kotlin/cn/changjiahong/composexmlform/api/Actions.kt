package cn.changjiahong.composexmlform.api

import cn.changjiahong.composexmlform.engine.diag.FrozenWriteException
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.AttrSite
import cn.changjiahong.composexmlform.engine.value.BooleanAdapter
import cn.changjiahong.composexmlform.engine.value.CoerceException
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.DoubleAdapter
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.IntAdapter
import cn.changjiahong.composexmlform.engine.value.LongAdapter
import cn.changjiahong.composexmlform.engine.value.StringAdapter
import cn.changjiahong.composexmlform.engine.value.TypeAdapter
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 动作处理器的执行现场。
 *
 * 引擎只提供 [env] / [data] / 动作名 / [args] —— **宿主能力（toast / 导航 / 权限）由调用方在
 * `actions { }` 的闭包里捕获**（`on("x") { toast("ok") }`，`toast` 是宿主作用域的函数）。
 *
 * 这是刻意的：多一个 `ActionHost` 接口，就多一层每个宿主都要实现的抽象，
 * 而闭包捕获已经够用。
 *
 * ## 实参
 *
 * `action.removeAt(2)` 里的 `2` 求值后按书写顺序进 [args] —— 在行上下文里
 * （`<Foreach item="item">`）写 `action.removeAt(index)` 拿到的是**这一行**的下标。
 * 实参是值不是引用：动作拿到的是点击那一刻求出来的结果。
 *
 * 类型化读取（[stringArg] / [longArg] …）走的是与属性槽位**同一套** `TypeAdapter`，
 * 只是收敛失败时返回默认值而不抛 —— 动作里读错一个实参不该让整页崩。
 */
class ActionScope(
    val env: Env,
    val data: DataObject,
    val name: String,
    /** 实参，按书写顺序。无参调动作为空表。 */
    val args: List<Value> = emptyList(),
) {

    /** 实参个数。宿主据此区分「`action.x()` 还是 `action.x(v)`」。 */
    val arity: Int get() = args.size

    /** 第 [index] 个实参；越界返回 [Absent]（与读不到字段同一约定，不是 `null`）。 */
    fun arg(index: Int): Value = args.getOrNull(index) ?: Absent

    fun stringArg(index: Int, default: String = ""): String = coerceArg(index, StringAdapter, default)

    fun longArg(index: Int, default: Long = 0L): Long = coerceArg(index, LongAdapter, default)

    fun intArg(index: Int, default: Int = 0): Int = coerceArg(index, IntAdapter, default)

    fun doubleArg(index: Int, default: Double = 0.0): Double = coerceArg(index, DoubleAdapter, default)

    fun booleanArg(index: Int, default: Boolean = false): Boolean =
        coerceArg(index, BooleanAdapter, default)

    /**
     * 收敛失败（缺参、类型不符）一律降级为 [default]。
     *
     * 不抛是因为动作已经在「用户点了一下」的时刻执行，此时抛错只能炸掉宿主事件循环，
     * 对定位问题没有帮助 —— 而「读到默认值」的表现（比如删了第 0 行）是可见的。
     */
    private fun <T> coerceArg(index: Int, adapter: TypeAdapter<T>, default: T): T = try {
        adapter.coerce(arg(index), AttrSite(tag = null, attribute = "实参 #$index"))
    } catch (_: CoerceException) {
        default
    }
}

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
            // args 必须一路带到这里：`action.removeAt(i)` 的 `i` 在 XML 求值时已经算好，
            // 曾经在这里被 `_` 丢掉，于是「删第 N 行」只能靠脚本自己改数据
            members[name] = Function { _, args ->
                handler.run(ActionScope(current.env, current.data, name, args))
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
