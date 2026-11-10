package cn.changjiahong.composexmlform.engine.env

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Path
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.WriteSource
import cn.changjiahong.composexmlform.engine.value.normalize
import cn.changjiahong.composexmlform.engine.value.readIn

/**
 * 一个名字绑定。
 *
 * [state] 是**响应式**的：`Foreach` 每帧原地改 `item` / `index`，而不是重建 env（R-2）。
 * 这一条同时保证了 `@{}` 捕获的 env 不会因为元素对象替换而失效。
 *
 * [origin] 是「这个值在 data 里的绝对位置」。没有它，`u = data.user; u.name = "x"`
 * 这条写入就发不出带路径的变更事件 —— 容器没有父指针，只能靠绑定记着来路（E-2）。
 *
 * [mutable] 对应脚本的 `val` / `var`（K-1）。`val` 的重复赋值在编译期就该报，
 * 这里保留标记是为了运行期兜底。
 */
class Slot internal constructor(
    internal val state: MutableState<Value>,
    val origin: Path?,
    val mutable: Boolean,
) {

    var value: Value
        get() = state.value
        internal set(value) {
            state.value = value
        }

    fun setValue(value: Value) {
        state.value = normalize(value)
    }

    override fun toString(): String = "$value${origin?.let { " @ ${it.display()}" } ?: ""}"
}

/**
 * 一层存储：`name → Slot`。
 *
 * 不做「一层一层往下传的 Map 拷贝」—— 子层只持有自己的 slot，查不到才问父层。
 */
class EnvStore {

    private val slots = LinkedHashMap<String, Slot>()

    /**
     * 建立绑定。**不发变更事件**（E-5）—— 建变量不是改数据。
     *
     * 同层重复定义同一个名字（`Foreach` 每帧写 `item`）走「原地改值」而不是重建，
     * 否则读它的 Composable 会整片重建。
     */
    fun define(
        name: String,
        value: Value,
        origin: Path? = null,
        mutable: Boolean = true,
    ): Slot {
        val existing = slots[name]
        if (existing != null) {
            existing.setValue(value)
            return existing
        }
        val slot = Slot(mutableStateOf(normalize(value)), origin, mutable)
        slots[name] = slot
        return slot
    }

    fun find(name: String): Slot? = slots[name]

    fun remove(name: String): Boolean = slots.remove(name) != null

    fun names(): List<String> = slots.keys.toList()
}

/**
 * 脚本函数体的局部变量层。
 *
 * 单独抽一个接口而不是让 env 依赖 eval 的 `CallFrame`：依赖方向必须是 `eval → env`，
 * 反过来会让「环境」知道「求值器」的存在（第 17 轮禁止的边）。
 */
interface LocalFrame {
    fun lookup(name: String): Slot?
}

/**
 * 环境：**一条链**，不是「全局表 + 局部表」两块。
 *
 * ```
 * Env(store, parent)          ← 子层：Foreach 行变量、组件局部量
 *   └── Env(store, parent)    ← 根层
 *         globals: data / config / action / http …
 * ```
 *
 * 全局对象**不占 slot**：它们按名字直接命中（[globals]），不进作用域链。
 * 取名也走同一张表，所以 `{{pv.name}}` 与 `{{user.name}}` 是同一条查找路径的不同分支。
 */
class Env internal constructor(
    internal val store: EnvStore,
    internal val parent: Env?,
    val schema: EnvSchema,
    private val globals: Map<String, Value>,
) {

    val globalNames: Set<String> get() = globals.keys

    fun child(): Env = Env(EnvStore(), this, schema, globals)

    /**
     * 在本层建绑定。保留字（全局对象名）一律拒绝 —— 否则 `{{data.x}}` 会被局部变量劫持（E-6）。
     */
    fun define(
        name: String,
        value: Value,
        origin: Path? = null,
        mutable: Boolean = true,
    ): Slot {
        require(!schema.isReserved(name)) {
            "『$name』是全局对象名（保留字），不能作为局部变量"
        }
        return store.define(name, value, origin, mutable)
    }

    /** 只看本层。 */
    fun find(name: String): Slot? = store.find(name)

    /** 逐层向上找，内层遮蔽外层（E-4）。 */
    fun chainFind(name: String): Slot? {
        var env: Env? = this
        while (env != null) {
            env.store.find(name)?.let { return it }
            env = env.parent
        }
        return null
    }

    /** 按名字取全局对象（`data` / `config` / `action` / 宿主注册的）。 */
    fun global(name: String): Value? = globals[name]

    // ── 读 ────────────────────────────────────────────────────

    /**
     * 五个分支，顺序固定（第 8 轮定稿）：
     *
     * ```
     * 绝对路径 → globals[root]                （跳过整条链，这也是性能快路径）
     * frame    → 脚本函数局部变量
     * 链       → 内层到外层
     * 命名全局 → globals[head]（如 pv）
     * 兜底     → 由槽位期望类型决定：Data → data，Function → action（V-13）
     * ```
     *
     * 兜底是**最后**一步而不是「先补前缀再查」：否则 `{config.x}` 会被解释成 `data.config.x`。
     */
    fun read(
        path: Path,
        hint: SlotKind = SlotKind.Data,
        frame: LocalFrame? = null,
    ): Value {
        val rootName = path.root
        if (rootName != null) {
            val target = globals[rootName] ?: return Absent
            return descend(target, path.rest)
        }

        val head = path.head ?: return Absent

        frame?.lookup(head)?.let { return descend(it.value, path.rest) }
        chainFind(head)?.let { return descend(it.value, path.rest) }
        globals[head]?.let { return descend(it, path.rest) }

        val fallbackName = schema.fallbackFor(hint) ?: return Absent
        val fallback = globals[fallbackName] ?: return Absent
        return descend(fallback, path.segments)
    }

    /** 字符串路径缩写：`read("data.user.name")`。 */
    fun read(raw: String, hint: SlotKind = SlotKind.Data, frame: LocalFrame? = null): Value {
        val path = Path.parse(raw) ?: return Absent
        return read(path, hint, frame)
    }

    // ── 写 ────────────────────────────────────────────────────

    /**
     * 写到 data 可达的位置，**全系统唯一发变更事件的入口**（E-5）。
     *
     * 路径来源三种：绝对路径本身 / 绑定的 [Slot.origin] / 兜底 `data`。
     * 纯局部标量（没有 origin 的绑定）不从这条路走 —— 那是 [Slot.setValue]，不发事件。
     *
     * 返回 `false` 表示路径不可达（中间层缺失、目标是 [cn.changjiahong.composexmlform.engine.value.HostObject]）；
     * 写只读容器则抛 `FrozenWriteException`，因为那是代码错误不是数据问题。
     */
    fun assign(path: Path, value: Value, source: WriteSource): Boolean {
        val target = resolveWriteTarget(path) ?: return false
        val rootName = target.root ?: return false
        val root = globals[rootName]
        if (root !is DataObject) return false
        return root.assign(target.rest, value, source)
    }

    fun assign(raw: String, value: Value, source: WriteSource): Boolean {
        val path = Path.parse(raw) ?: return false
        return assign(path, value, source)
    }

    /** 把任意路径归一成「绝对路径」，位置不可写时返回 `null`。 */
    private fun resolveWriteTarget(path: Path): Path? {
        if (path.isAbsolute) return path

        val head = path.head ?: return null

        if (globals.containsKey(head)) return Path(head, path.segments)

        val slot = chainFind(head)
        if (slot != null) {
            val origin = slot.origin ?: return null
            return path.replacePrefix(1, origin)
        }

        val fallback = schema.fallbackFor(SlotKind.Data) ?: return null
        return path.fallbackTo(fallback)
    }

    override fun toString(): String = "Env(depth=${depth()}, locals=${store.names()})"

    private fun depth(): Int {
        var count = 0
        var env: Env? = this
        while (env != null) {
            count++
            env = env.parent
        }
        return count
    }

    companion object {

        /** 建根环境。全局对象必须在建它之前准备好（E-3）。 */
        fun root(schema: EnvSchema, globals: Map<String, Value>): Env =
            Env(EnvStore(), null, schema, globals)

        /** 省略 schema 的常见写法：能力由对象自己推导（`frozen` 就是「不能写」）。 */
        fun root(
            objects: Map<String, DataObject>,
            callableNames: Set<String> = setOf("action"),
        ): Env = root(EnvSchema.from(objects, callableNames), objects)
    }
}

/** 下降：逐段读。任一节点不是容器就 [Absent]（静默传播，P-4）。 */
private fun descend(start: Value, segments: List<String>): Value {
    var current = start
    for (segment in segments) {
        if (current !is DataObject && current !is DataArray) return Absent
        current = readIn(current, segment)
    }
    return current
}
