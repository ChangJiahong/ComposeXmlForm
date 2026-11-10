package cn.changjiahong.composexmlform.engine.env

import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.Value

/** 全局对象的能力。解析期只能看到这个，看不到值（M-3）。 */
enum class GlobalKind {

    /** 可写可读：`data`、宿主注册的运行期状态对象（`pv`）。 */
    Writable,

    /** 只读可读：`config`、以及成员全是函数的 `action` / `http`。 */
    ReadOnly,

    /** 成员全是函数：`action`、`http`、`storage`。 */
    Callable,
}

/**
 * 解析期可见的环境形状：**名字 + 能力**，不含任何值。
 *
 * 它的存在只为两件事（第 16 轮）：
 * 1. `{{dta.user}}` 首段不在表里 → 解析期报错（上一版静默返回 null）；
 * 2. `VarAttr` 槽位收到只读对象 → 解析期报错（上一版要等到运行期看容器类型）。
 *
 * **不校验成员存在**（M-3）：`data.user` 里有没有 `user`、`config` 有没有某个键、`action`
 * 注册了哪些函数，全是运行期内容。越界校验会同时废掉增量注册、运行期换字典、动态字段三件事。
 */
class EnvSchema(
    val globals: Map<String, GlobalKind>,
    /** 相对路径未命中时补哪个全局对象，由槽位期望的类型决定（V-13）。 */
    val fallback: Map<SlotKind, String> = DEFAULT_FALLBACK,
) {

    /** 保留字集合。这些名字不能被 `define` 成局部变量（E-6）。 */
    val reserved: Set<String> = globals.keys

    fun kindOf(name: String): GlobalKind? = globals[name]

    fun isReserved(name: String): Boolean = globals.containsKey(name)

    fun fallbackFor(hint: SlotKind): String? = fallback[hint]

    /** 换了全局对象就必须重新解析（M-4），所以 schema 需要一个稳定的相等语义。 */
    override fun equals(other: Any?): Boolean =
        other is EnvSchema && globals == other.globals && fallback == other.fallback

    override fun hashCode(): Int = globals.hashCode() * 31 + fallback.hashCode()

    override fun toString(): String = "EnvSchema(${globals.keys.joinToString()})"

    companion object {

        val DEFAULT_FALLBACK: Map<SlotKind, String> = mapOf(
            SlotKind.Data to "data",
            SlotKind.Function to "action",
        )

        /** 最小 schema：只有 `data` 与 `action`。宿主不额外注册全局对象时用它。 */
        val Minimal: EnvSchema = EnvSchema(
            mapOf(
                "data" to GlobalKind.Writable,
                "action" to GlobalKind.Callable,
            ),
        )

        /**
         * 由实际对象推导 schema。
         *
         * 为什么由值推导：能力本来就来自对象的实现（`frozen` 就是「不能写」），
         * 让宿主再手写一份能力表等于给同一件事两个真相。手写入口保留给「只有名字、暂时没有值」的场景。
         *
         * [callableNames] 用来兜住「此刻还是空对象、但语义上是函数容器」的全局对象 ——
         * `action` 在宿主还没注册任何函数时必须仍是 `Callable`，否则解析期会把它当只读数据。
         */
        fun from(
            globals: Map<String, DataObject>,
            callableNames: Set<String> = setOf("action"),
        ): EnvSchema = EnvSchema(
            globals.mapValues { (name, obj) -> kindOfObject(obj, name in callableNames) }
        )

        fun kindOfObject(obj: DataObject, callableByConvention: Boolean = false): GlobalKind = when {
            !obj.frozen -> GlobalKind.Writable
            callableByConvention -> GlobalKind.Callable
            obj.isCallableOnly() -> GlobalKind.Callable
            else -> GlobalKind.ReadOnly
        }

        /** 成员全是函数。空对象不算 —— 那是「还没注册」。 */
        private fun DataObject.isCallableOnly(): Boolean {
            val names = keys()
            if (names.isEmpty()) return false
            return names.all { member(it) is cn.changjiahong.composexmlform.engine.value.Function }
        }
    }
}
