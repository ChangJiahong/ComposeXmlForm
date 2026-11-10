package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.HostObject
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.ValueKind
import cn.changjiahong.composexmlform.engine.value.display
import cn.changjiahong.composexmlform.engine.value.kindOf

/**
 * 宿主扩展的一次「认领」尝试：能处理就返回函数，不归我管就返回 `null` 弃权。
 *
 * 弃权而不是报错，是链式查找的前提 —— 多个 provider 依次尝试，谁先认领谁生效（F-1）。
 */
fun interface MethodProvider {
    fun provide(value: Value, name: String): Function?
}

/**
 * 宿主扩展链，**每个 runtime 一份**。
 *
 * 为什么不做成全局可变单例：同一个进程里可能有两个 runtime（两个页面、两套注册），
 * 全局单例会让它们互相看见对方的方法；测试之间更会串用例。
 */
class MethodExtensions internal constructor(internal val providers: List<MethodProvider>) {

    internal fun find(value: Value, name: String): Function? {
        for (provider in providers) {
            provider.provide(value, name)?.let { return it }
        }
        return null
    }

    val isEmpty: Boolean get() = providers.isEmpty()

    operator fun plus(other: MethodExtensions): MethodExtensions =
        if (other.isEmpty) this else if (isEmpty) other else MethodExtensions(providers + other.providers)

    companion object {
        val Empty: MethodExtensions = MethodExtensions(emptyList())
    }

    override fun toString(): String = "MethodExtensions(${providers.size})"
}

/** 声明式写法：`methodExtensions { on(ValueKind.Array, "sum", …) }`。 */
fun methodExtensions(block: MethodExtensionsBuilder.() -> Unit): MethodExtensions =
    MethodExtensionsBuilder().apply(block).build()

class MethodExtensionsBuilder internal constructor() {

    private val providers = ArrayList<MethodProvider>()

    /**
     * 按值类型注册，签名 `(target, args) -> Value`。
     *
     * 与内置方法同名**当场报错**（F-3）：先注册先命中的话，`list.supend` 与内置 `append`
     * 只差一个字母时行为会取决于注册顺序，出问题时没有任何线索。
     */
    fun on(kind: ValueKind, name: String, fn: Function) {
        require(!BuiltinMethods.conflicts(kind, name)) {
            "『$name』已经是 ${kind.display} 的内置方法，不能重复注册（换一个名字，或用 provide 完全接管）"
        }
        providers += MethodProvider { value, member ->
            if (member == name && kindOf(value) == kind) fn else null
        }
    }

    /**
     * 按宿主业务类注册：只在 `target` 是 [HostObject] 且名字匹配时认领，其余情况自动弃权。
     *
     * 拒绝认领只发生在**查找期**（靠类型与名字判断）。一旦认领，方法一定被调用 ——
     * 查找期拿不到实参，所以「按参数决定归谁管」要写成 [provide]。
     * 没有结果时返回 [Absent]（与「路径不存在」同一套语义），不要返回 `null`（那是「值为空」）。
     */
    fun onHost(name: String, fn: (target: HostObject, args: List<Value>) -> Value) {
        providers += MethodProvider { value, member ->
            if (member != name || value !is HostObject) {
                null
            } else {
                Function { target, args -> fn(target as HostObject, args) }
            }
        }
    }

    /** 完全自定义：需要按对象内容 / 参数个数决定归属时用它。 */
    fun provide(provider: MethodProvider) {
        providers += provider
    }

    internal fun build(): MethodExtensions = MethodExtensions(providers.toList())
}
