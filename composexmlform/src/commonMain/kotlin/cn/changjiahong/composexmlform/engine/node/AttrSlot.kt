package cn.changjiahong.composexmlform.engine.node

import cn.changjiahong.composexmlform.engine.value.AttrSite
import cn.changjiahong.composexmlform.engine.value.Path

/**
 * 一个属性**在某个节点上**的具体写法：声明 + 写法 + 现场。
 *
 * 三者缺一不可：
 * - [def] 说「该是什么类型、缺了怎么办」；
 * - [ref] 说「这次写的是什么形态」（字面量 / 绑定 / 计算 / 函数）；
 * - [site] 说「出错时报在哪」（标签、属性名、源码区间）。
 *
 * **不含 env、不含缓存**。求值要 env 与扩展链，而那是渲染层的事（S7）——
 * 一旦把 env 塞进 slot，节点树就绑死在一棵具体的环境树上，同一个 `Node` 无法在
 * 两个 runtime 里复用，测试也得先造 env 才能断言结构。
 *
 * 读取入口在 `engine.eval` 包（`AttrSlot.read`），不在本包 —— 见 `PLAN.md` S5 的出口条件。
 */
class AttrSlot<T> internal constructor(
    val def: AttrDef<T>,
    internal val ref: AttrRef,
    val site: AttrSite,
) {

    /** 这个属性在 XML 里写了没有。`false` 时读取走 `def.default`。 */
    val present: Boolean get() = ref !== AttrRef.Absent

    /**
     * 写法是不是 `{{path}}`。
     *
     * 渲染层靠它判断「这条属性有没有可回写目标」，而不是靠 `def.writable` ——
     * 可写槽位也可能没写（用默认值），那种情况没有目标可写。
     */
    val isBinding: Boolean get() = ref is AttrRef.Bind

    /**
     * 绑定指向的路径，仅 `{{path}}` 有。
     *
     * 给渲染层的 `onValueChange` 用：`VarAttr` 的值要写回**这条路径**，
     * 而不是笼统地写 `data.<属性名>`。
     */
    val boundPath: Path? get() = (ref as? AttrRef.Bind)?.path

    override fun toString(): String = "AttrSlot(${def.name} = ${if (present) ref.describe() else "默认"})"
}
