package cn.changjiahong.composexmlform.api.decl

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import cn.changjiahong.composexmlform.engine.eval.EvalContext
import cn.changjiahong.composexmlform.engine.node.Attr
import cn.changjiahong.composexmlform.engine.node.AttrDef
import cn.changjiahong.composexmlform.engine.node.AttrSet
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.render.DiagnosticSink
import cn.changjiahong.composexmlform.engine.render.EventHandler
import cn.changjiahong.composexmlform.engine.render.buildHandler
import cn.changjiahong.composexmlform.engine.render.renderValue
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.AnyAdapter
import cn.changjiahong.composexmlform.engine.value.AttrSite
import cn.changjiahong.composexmlform.engine.value.DoubleAdapter
import cn.changjiahong.composexmlform.engine.value.FloatAdapter
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.LongAdapter
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.TypeAdapter
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.WriteSource
import kotlin.reflect.KProperty

/**
 * 一个标签的属性表。**只在一处出现**：节点构造函数里的 `props.long("max", 5L)` 顺手就声明了。
 *
 * 为什么要先建一次「原型」：解析器需要属性白名单，而那必须早于任何节点实例存在。
 * `component()` 用一份空 [Props] 把节点构造一次，构造期调用的每个访问器把 `AttrDef` 记进来，
 * 于是「声明」与「读取」天然只有一份来源 —— 不存在 Attrs 对象、构造函数参数、`create` 里
 * `attrs.attr(...)` 三处各写一遍的可能。
 *
 * [resolve] 两个模式：声明期登记；运行期**复用**声明期那一份，保证默认值只有一份。
 */
internal class Schema(val tag: String) {

    private val order = ArrayList<AttrDef<*>>(8)
    private val byName = HashMap<String, AttrDef<*>>(8)

    val decls: List<AttrDef<*>> get() = order

    fun <T> resolve(created: AttrDef<T>): AttrDef<T> {
        val existing = byName[created.name]
        if (existing == null) {
            byName[created.name] = created
            order += created
            return created
        }
        require(existing.type === created.type) {
            "『$tag』的属性『${created.name}』被声明成两种类型：${existing.type.typeName} / ${created.type.typeName}"
        }
        require(existing.writable == created.writable) {
            "『$tag』的属性『${created.name}』可写性前后不一致"
        }
        @Suppress("UNCHECKED_CAST")
        return existing as AttrDef<T>
    }
}

/**
 * 节点的属性现场。两个身份合一：
 *
 * | 时机 | `slots` | `ctx` | 用途 |
 * | --- | --- | --- | --- |
 * | 声明期（原型） | `null` | `null` | 收集属性表，读值是声明默认值 |
 * | 运行期（绑定） | 解析期的槽位 | 本行的 env | 读值 / 写回 |
 *
 * **每次渲染都重新绑定一份**，而不是把 env 挂在节点上：同一个节点实例在 `Foreach` 里
 * 会被渲染 N 行，env 挂在节点上等于第 2 行读第 1 行的数据。绑定的产物是一个新节点
 * （`create(props)` 重建），`RenderNode` 用 `remember(node, env)` 记住它，所以每行一份、
 * 行内稳定 —— 事件处理器捕获的也是**自己那一行**的现场。
 */
class Props internal constructor(
    val id: String,
    val tag: String,
    internal val schema: Schema,
    private val slots: Map<String, AttrSlot<*>>?,
    internal val ctx: EvalContext?,
    internal var sink: DiagnosticSink,
    val children: List<Node>,
    internal val create: ((Props) -> Node)?,
) {

    internal fun slotOf(name: String): AttrSlot<*>? = slots?.get(name)

    /**
     * 声明 + 绑定一条属性。
     *
     * `T` 只由 [fallback] 推断（`AttrDef` 的类型参数是声明期的，带 default 就是非空）：
     * `field<Long>(name, Attr.int(name, 5L), 5L)` → `Prop<Long>`，
     * `field<Long?>(name, Attr.int(name), null)` → `Prop<Long?>`。
     */
    internal fun <T> field(name: String, declared: AttrDef<*>, fallback: T?): Prop<T> =
        Prop(name, this, schema.resolve(declared), fallback)

    internal fun rebind(ctx: EvalContext, sink: DiagnosticSink): Props =
        Props(id, tag, schema, slots, ctx, sink, children, create)

    // ── 只读属性 ─────────────────────────────────────────────
    //
    // 访问器自带类型：`long` 一定收敛成 Long，`string` 一定收敛成 String。
    // 带 default 的返回非空类型，不带 default 的返回可空类型 —— 这是「属性可以没有值」
    // 在类型上的唯一表达，不需要另外记一个 `present`。

    fun long(name: String, default: Long): Prop<Long> =
        field(name, Attr.int(name, default = default), default)

    fun long(name: String): Prop<Long?> = field(name, Attr.int(name), null)

    fun int(name: String, default: Int): Prop<Int> =
        field(name, Attr.of(name, IntAdapter2, default = default), default)

    fun float(name: String, default: Float): Prop<Float> =
        field(name, Attr.of(name, FloatAdapter, default = default), default)

    fun float(name: String): Prop<Float?> = field(name, Attr.of(name, FloatAdapter), null)

    fun double(name: String, default: Double): Prop<Double> =
        field(name, Attr.of(name, DoubleAdapter, default = default), default)

    fun string(name: String, default: String): Prop<String> =
        field(name, Attr.string(name, default = default), default)

    fun string(name: String): Prop<String?> = field(name, Attr.string(name), null)

    fun bool(name: String, default: Boolean): Prop<Boolean> =
        field(name, Attr.bool(name, default = default), default)

    fun bool(name: String): Prop<Boolean?> = field(name, Attr.bool(name), null)

    /** 保留原始类型：`Select` 的 `options` / `value` 用它（`"1"` 与 `1` 不等价）。 */
    fun value(name: String): Prop<Value> = field(name, Attr.of(name, AnyAdapter), null)

    /** 结构化属性（`Foreach` 的 `item` / `index` / `key`）：只收裸标识符。 */
    fun identifier(name: String): Prop<String?> =
        field(name, Attr.string(name, structural = true), null)

    // ── 可写属性（`{{path}}` 双向绑定） ───────────────────────

    fun varLong(name: String, default: Long): Prop<Long> =
        field(name, Attr.varInt(name, default = default), default)

    fun varFloat(name: String, default: Float): Prop<Float> =
        field(name, Attr.varOf(name, FloatAdapter, default = default), default)

    fun varString(name: String, default: String): Prop<String> =
        field(name, Attr.varString(name, default = default), default)

    fun varString(name: String): Prop<String?> = field(name, Attr.varString(name), null)

    fun varBool(name: String, default: Boolean): Prop<Boolean> =
        field(name, Attr.varBool(name, default = default), default)

    fun varValue(name: String): Prop<Value> = field(name, Attr.varOf(name, AnyAdapter), null)

    // ── 事件 ─────────────────────────────────────────────────

    fun action(name: String): Action = Action(this, schema.resolve(Attr.func(name)))
}

/**
 * 一条属性的读写句柄。**它就是值本身**：`node.value` 是 `Long`，赋值即写回。
 *
 * 三条路径（与旧的 `rememberField` 同一套语义，只是藏进了委托）：
 *
 * | 情况 | 读 | 写 |
 * | --- | --- | --- |
 * | `value="{{path}}"` | 绑定路径的实时值（读即订阅） | `env.assign`，发 `UserBinding` |
 * | 没写 / 写字面量 | 字面量或声明默认值 | 就地改**本行**的局部 state（非受控输入框） |
 *
 * 局部 state 放在这里而不是 composition 里：`Props` 已经是一行一份（`remember(node, env)`），
 * 所以「同一行」与「同一个 Prop 实例」是同一件事。
 */
class Prop<T> internal constructor(
    val name: String,
    private val props: Props,
    private val def: AttrDef<*>,
    private val fallback: T?,
) {

    private var local: MutableState<Any?>? = null

    /** 读。没有绑定路径时，「写过」优先于「写了什么」。 */
    fun get(): T {
        val slot = props.slotOf(name)
        val ctx = props.ctx
        val raw: Any? = when {
            slot == null || ctx == null -> def.defaultValue()

            slot.boundPath != null -> slot.renderValue(ctx, props.sink)

            else -> {
                // 非受控：**第一次读就把字面量/默认值灌进局部 state**，之后永远读 state。
                // 少了这一步，第一次读不会订阅任何东西，写入后界面不刷新 ——
                // 旧实现把 `mutableStateOf(initial)` 建在 composition 里，天然不会漏这一环。
                val state = local
                    ?: mutableStateOf<Any?>(slot.renderValue(ctx, props.sink)).also { local = it }
                state.value
            }
        }
        @Suppress("UNCHECKED_CAST")
        return (if (raw === Absent) fallback else raw) as T
    }

    /** 写。绑定了就写数据，没绑定就写本行局部 state。 */
    fun set(value: T) {
        val slot = props.slotOf(name)
        val ctx = props.ctx
        val path = slot?.boundPath
        if (slot != null && path != null && ctx != null) {
            if (ctx.env.assign(path, value.asValue(), WriteSource.UserBinding)) return
        }
        val state = local ?: mutableStateOf<Any?>(null).also { local = it }
        state.value = value
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = get()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)
}

/**
 * 事件属性：`val onClick by props.action("onClick")`，类型是 [EventHandler]?。
 *
 * 记忆化到「捕获的局部快照」不变为止。`Foreach` 行内的事件必须捕获**那一行**的
 * `item` / `index`（R-3），所以比较的是快照内容而不是引用；元素换实例时快照变，处理器重建。
 */
class Action internal constructor(
    private val props: Props,
    private val def: AttrDef<*>,
) {

    private var memoKey: Map<String, Value>? = null
    private var memo: EventHandler? = null

    fun get(): EventHandler? {
        val slot = props.slotOf(def.name) as? AttrSlot<Function> ?: return null
        val ctx = props.ctx ?: return null
        val captured = ctx.env.captureLocals()
        if (captured == memoKey) return memo
        val handler = slot.buildHandler(ctx, captured, props.sink)
        memoKey = captured
        memo = handler
        return handler
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): EventHandler? = get()
}

// ── 内部小工具 ────────────────────────────────────────────────

/** 写回数据时把 Kotlin 侧类型收敛成 `Value` 的规范形态（`Float` 不在其中）。 */
private fun Any?.asValue(): Value = if (this is Float) this.toDouble() else this

/** `Int` 属性：数据里只有 Long，宿主侧更常用 Int。 */
private object IntAdapter2 : TypeAdapter<Int> {

    override val slotKind: SlotKind = SlotKind.Data

    override val typeName: String = "整数"

    override fun literal(raw: String, site: AttrSite): Value = LongAdapter.literal(raw, site)

    override fun coerce(value: Value, site: AttrSite): Int = LongAdapter.coerce(value, site).toInt()

    override fun default(): Int? = null
}

/**
 * 从 `AttrSet` 造一份可用的 [Props]。
 *
 * 已声明的属性**一次取完**：`AttrSet.attr` 的副作用是记「已消费」，
 * 逐条取完等于让 `finish()` 只剩「未知属性」一件事要报，也保证不会漏读某条声明。
 */
internal fun propsOf(
    schema: Schema,
    id: String,
    attrs: AttrSet,
    children: List<Node>,
    create: ((Props) -> Node)?,
): Props {
    val slots = HashMap<String, AttrSlot<*>>(schema.decls.size)
    for (def in schema.decls) slots[def.name] = attrs.attr(def)
    return Props(id, schema.tag, schema, slots, ctx = null, sink = DiagnosticSink.None, children, create)
}
