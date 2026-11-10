package cn.changjiahong.composexmlform.engine.node

import cn.changjiahong.composexmlform.engine.diag.Code

/**
 * 子节点约束（N-4）：**声明化**，而不是让每个节点在构造函数里自己抛。
 *
 * 声明成数据的好处是它可被解析层统一校验（S6 拿着标签列表就能判），
 * 也可以在文档里直接列出来，不必读实现。
 */
sealed interface ChildRule {

    /** 不能有子节点。`Text` / `Image` 这类。 */
    object None : ChildRule

    /** 任意数量、任意标签。`Column` / `Row` / `Box`。 */
    object Any : ChildRule

    /** 至少一个子节点。容器类组件用它兜住「空布局」这种配置疏漏。 */
    object AtLeastOne : ChildRule

    /** 只允许这些标签。`If` 只允许 `Then` / `Else`。 */
    data class Only(val tags: Set<String>) : ChildRule

    /**
     * 校验一组子标签。**纯函数**：只看标签，不看节点。
     *
     * 为什么不接受 `List<Node>`：`Node` 上没有 `tag`（M1 之后结点不存标签），
     * 硬塞一个 `tag` 字段等于让每个子类多带一份可以从 `NodeType` 查到的东西。
     * 解析层手上有 `XmlElement.tag`，直接传字符串列表最省。
     */
    fun check(childTags: List<String>): ChildRuleViolation? = when (this) {
        None ->
            if (childTags.isEmpty()) null
            else ChildRuleViolation(Code.ChildNotAllowed, "这里不能有子节点，却给了 ${childTags.size} 个")

        Any -> null

        AtLeastOne ->
            if (childTags.isEmpty()) ChildRuleViolation(Code.ChildRequired, "至少需要一个子节点")
            else null

        is Only -> childTags.firstOrNull { it !in tags }?.let {
            ChildRuleViolation(
                Code.ChildTagMismatch,
                "只允许 ${tags.sorted().joinToString("、")}，不能是『$it』",
            )
        }
    }
}

/** 一条子节点违规。只带码与消息，位置由调用方补（它才知道是哪个标签的第几个孩子）。 */
class ChildRuleViolation(val code: Code, val message: String) {

    override fun toString(): String = "[$code] $message"
}
