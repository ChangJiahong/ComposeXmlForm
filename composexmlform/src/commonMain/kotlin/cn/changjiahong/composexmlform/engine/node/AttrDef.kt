package cn.changjiahong.composexmlform.engine.node

import cn.changjiahong.composexmlform.engine.value.BooleanAdapter
import cn.changjiahong.composexmlform.engine.value.DoubleAdapter
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.FunctionAdapter
import cn.changjiahong.composexmlform.engine.value.LongAdapter
import cn.changjiahong.composexmlform.engine.value.SlotKind
import cn.changjiahong.composexmlform.engine.value.StringAdapter
import cn.changjiahong.composexmlform.engine.value.TypeAdapter

/**
 * 一条属性声明。
 *
 * 这是**显式 schema**（A-3）：标签能用哪些属性、什么类型、缺了怎么办，全在这里写死，
 * 不再靠「谁先 `attr()` 谁赢」反推 —— 后者拼错属性名只会静默多出一个没人读的键。
 *
 * [writable] 区分 `VarAttr` 与 `ValAttr`（A-1）。落成布尔而不是两个类，是因为**读取路径完全相同**，
 * 差别只在一处：可写槽位只接受 `{{path}}`，且渲染层会给它挂 `onValueChange`。
 * 建两个类会逼所有消费方写 `when (slot)`，而它们 99% 的代码是一样的。
 *
 * [default] 是**声明级**默认值，优先于 [TypeAdapter.default] 的类型空值：
 * `Attr.int("size", default = 14L)` 缺省就是 14，而不是「没值」。
 *
 * [structural] 标记「结构属性」：它的值不是数据，而是**在本节点子树上生效的名字**
 * （`Foreach` 的 `item` / `index` / `key`）。解析期只收裸标识符（N-2），
 * 并在解析子节点之前压进作用域栈（第 12 轮）。
 */
class AttrDef<T>(
    val name: String,
    val type: TypeAdapter<T>,
    val required: Boolean = false,
    val default: T? = null,
    val writable: Boolean = false,
    val structural: Boolean = false,
) {

    /** 声明期常量（A-2）：槽位是不是函数，只有这里知道 —— Kotlin 泛型运行期擦除。 */
    val slotKind: SlotKind get() = type.slotKind

    val isFunction: Boolean get() = slotKind == SlotKind.Function

    /** 缺失时的值：声明值优先，没有就退到类型的空值（`String` → `""`，数字 → `null`）。 */
    fun defaultValue(): T? = default ?: type.default()

    override fun toString(): String =
        "AttrDef($name: ${type.typeName}${if (writable) ", var" else ""}${if (required) ", required" else ""})"
}

/**
 * 属性声明的工厂。
 *
 * 刻意不做 `Attr.any()`：`AnyAdapter` 恒返回 `null` 作默认值，等于「这个槽位永远没有默认」，
 * 那是 `Attr.of(name, AnyAdapter)` 就够的表达，多一个快捷方式只会让人以为它有特殊语义。
 */
object Attr {

    fun string(
        name: String,
        required: Boolean = false,
        default: String? = null,
        structural: Boolean = false,
    ): AttrDef<String> = AttrDef(name, StringAdapter, required, default, structural = structural)

    fun int(name: String, required: Boolean = false, default: Long? = null): AttrDef<Long> =
        AttrDef(name, LongAdapter, required, default)

    fun double(name: String, required: Boolean = false, default: Double? = null): AttrDef<Double> =
        AttrDef(name, DoubleAdapter, required, default)

    fun bool(name: String, required: Boolean = false, default: Boolean? = null): AttrDef<Boolean> =
        AttrDef(name, BooleanAdapter, required, default)

    /**
     * 函数槽位（`onClick` 这类）。**只接受 `@{…}`**。
     *
     * 没有 `default`：给一个「默认什么都不做」的函数会让「没写 onClick」和
     * 「写了空 onClick」在运行期无法区分，而前者往往是配置疏漏。
     */
    fun func(name: String, required: Boolean = false): AttrDef<Function> =
        AttrDef(name, FunctionAdapter, required, null)

    /** 任意适配器。宿主自定义类型（日期、枚举）从这里进来。 */
    fun <T> of(
        name: String,
        type: TypeAdapter<T>,
        required: Boolean = false,
        default: T? = null,
        structural: Boolean = false,
    ): AttrDef<T> = AttrDef(name, type, required, default, structural = structural)

    /** 可写槽位（`VarAttr`）：只接受 `{{path}}`，渲染层会回写。 */
    fun <T> varOf(name: String, type: TypeAdapter<T>, default: T? = null): AttrDef<T> =
        AttrDef(name, type, required = false, default = default, writable = true)

    fun varString(name: String, default: String? = null): AttrDef<String> =
        varOf(name, StringAdapter, default)

    fun varInt(name: String, default: Long? = null): AttrDef<Long> =
        varOf(name, LongAdapter, default)

    fun varDouble(name: String, default: Double? = null): AttrDef<Double> =
        varOf(name, DoubleAdapter, default)

    fun varBool(name: String, default: Boolean? = null): AttrDef<Boolean> =
        varOf(name, BooleanAdapter, default)
}
