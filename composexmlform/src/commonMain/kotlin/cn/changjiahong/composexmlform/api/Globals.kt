package cn.changjiahong.composexmlform.api

import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.Fn
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.normalize

/**
 * 宿主注册的全局对象（`http` / `storage` / `pv` …）。
 *
 * 与 `data` / `config` / `action` 的差别只有两点：**名字是宿主起的**，以及
 * 它们不在 `DynamicLayout` 的固定参数里。语义上完全同级 —— 一旦注册，
 * `{http.get(url)}` 里的 `http` 就是解析期可见的全局对象名（保留字）。
 *
 * 对象默认是**冻结**的：全局常量不该被模板改写。需要可写状态时自己传一个
 * `MutableDataObject`（`object_("pv", someObject)`），由宿主掌控写入时机。
 *
 * `fn` 注册的是**全局函数**，挂到 `action` 下 —— 与 `actions { }` 同一张表，
 * 因此模板里 `{ click() }` 的裸调用能落到它（V-13 的兜底）。
 */
class Globals internal constructor(
    internal val objects: Map<String, DataObject>,
    internal val functions: Map<String, Function>,
) {

    /** 组合两组全局对象。同名的后者覆盖前者（显式拼装，不算手误）。 */
    operator fun plus(other: Globals): Globals = Globals(
        objects = objects + other.objects,
        functions = functions + other.functions,
    )

    val names: Set<String> get() = objects.keys

    /** 里面只有函数的那部分。 */
    val functionNames: Set<String> get() = functions.keys

    override fun toString(): String =
        "Globals(objects=${objects.keys.joinToString()}, fns=${functions.keys.joinToString()})"

    companion object {
        val Empty: Globals = Globals(emptyMap(), emptyMap())
    }
}

/** 构造一组全局对象。 */
fun globals(block: GlobalsBuilder.() -> Unit): Globals = GlobalsBuilder().apply(block).build()

/** DSL 标记：防止嵌套的 `object_ { }` 里的 `fn` 落到外层。 */
@DslMarker
annotation class GlobalsDsl

@GlobalsDsl
class GlobalsBuilder internal constructor() {

    private val objects = LinkedHashMap<String, DataObject>()
    private val functions = LinkedHashMap<String, Function>()

    /**
     * 建一个只读对象：成员全是函数（`http.get`）或常量（`theme.primary`）。
     *
     * 只读是刻意的 —— 全局对象是**能力**不是状态。需要可变状态用
     * `object_(name, MutableDataObject)` 那一版，由宿主自己负责写入。
     */
    fun object_(name: String, block: ObjectBuilder.() -> Unit) {
        register(name, ObjectBuilder().apply(block).build())
    }

    /** 直接注册已建好的对象；要可写就传 `frozen = false` 的实例。 */
    fun object_(name: String, value: DataObject) {
        register(name, value)
    }

    /** 全局函数，挂到 `action` 下。 */
    fun fn(name: String, fn: Function) {
        requireIdentifier(name, "函数名")
        require(name !in functions) { "全局函数『$name』重复注册" }
        functions[name] = fn
    }

    fun fn(name: String, block: (target: Value, args: List<Value>) -> Value) {
        fn(name, Fn.of(block))
    }

    /** 挂起函数：只能在 `await f(x)` 里调用，需要 [ScriptMode.Full]。 */
    fun suspendFn(name: String, block: suspend (target: Value, args: List<Value>) -> Value) {
        fn(name, Fn.suspendOf(block))
    }

    internal fun build(): Globals = Globals(objects.toMap(), functions.toMap())

    private fun register(name: String, value: DataObject) {
        requireIdentifier(name, "全局对象名")
        require(name !in RESERVED) { "『$name』是引擎保留的全局对象名，换一个" }
        require(name !in objects) { "全局对象『$name』重复注册" }
        objects[name] = value
    }

    private companion object {
        /** `data` / `config` / `action` 由 `DynamicLayout` 自己建，宿主不能再注册一份。 */
        val RESERVED = setOf("data", "config", "action")
    }
}

@GlobalsDsl
class ObjectBuilder internal constructor() {

    private val members = LinkedHashMap<String, Any?>()

    fun fn(name: String, fn: Function) {
        requireIdentifier(name, "函数名")
        require(name !in members) { "成员『$name』重复注册" }
        members[name] = fn
    }

    fun fn(name: String, block: (target: Value, args: List<Value>) -> Value) {
        fn(name, Fn.of(block))
    }

    fun suspendFn(name: String, block: suspend (target: Value, args: List<Value>) -> Value) {
        fn(name, Fn.suspendOf(block))
    }

    /** 常量成员。值会被规范化（`Int` → `Long`，`Map` → 对象，见 `normalize`）。 */
    fun const(name: String, value: Any?) {
        requireIdentifier(name, "成员名")
        require(name !in members) { "成员『$name』重复注册" }
        members[name] = value
    }

    internal fun build(): DataObject = normalize(members, frozen = true) as DataObject
}

/**
 * 标识符校验。
 *
 * 全局函数名与对象名最终都要在模板里写出来（`{http.get(u)}`），不是合法标识符就永远调不到。
 * 在构建期报错，比运行期「找不到这个名字」清楚得多。
 */
internal fun requireIdentifier(name: String, what: String) {
    require(name.isNotEmpty()) { "${what}不能为空" }
    require(name[0].isLetter() || name[0] == '_') { "${what}『$name』必须以字母或下划线开头" }
    require(name.all { it.isLetterOrDigit() || it == '_' }) { "${what}『$name』只能包含字母、数字与下划线" }
}
