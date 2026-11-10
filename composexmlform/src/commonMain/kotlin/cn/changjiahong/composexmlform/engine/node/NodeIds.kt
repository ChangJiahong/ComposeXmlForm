package cn.changjiahong.composexmlform.engine.node

/**
 * 结构化 id 的生成与解读（N-5）。
 *
 * ```
 * 0            根
 * 0.0          根的第 0 个子节点
 * 0.1.2        根的第 1 个子节点的第 2 个子节点
 * ```
 *
 * 为什么不用自增计数器：自增 id 依赖「解析顺序」这个全局状态，同一份 XML 解析两次
 * 编号可能不同（并行解析、增量解析都会破坏它），而 `remember` 的 key 与诊断归并都需要
 * **同输入同输出**。位置路径天生满足这一条。
 */
object NodeIds {

    /** 根节点的 id。 */
    const val Root: String = "0"

    fun root(): String = Root

    /** 第 [index] 个子节点的 id。 */
    fun child(parent: String, index: Int): String {
        require(index >= 0) { "子节点下标不能为负：$index" }
        return "$parent.$index"
    }

    /** 层数：`0` 是 1 层。 */
    fun depth(id: String): Int = if (id.isEmpty()) 0 else id.count { it == '.' } + 1

    /** 本节点在父节点里的下标；根没有父，返回 `null`。 */
    fun indexOf(id: String): Int? =
        if ('.' !in id) null else id.substringAfterLast('.').toIntOrNull()

    fun parentOf(id: String): String? =
        if ('.' !in id) null else id.substringBeforeLast('.')

    /** [id] 是不是 [ancestor] 自己或它的后代 —— 诊断归并、列表 key 前缀判断用。 */
    fun isUnder(id: String, ancestor: String): Boolean =
        id == ancestor || id.startsWith("$ancestor.")
}
