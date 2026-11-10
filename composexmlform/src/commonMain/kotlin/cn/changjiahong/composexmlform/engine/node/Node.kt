package cn.changjiahong.composexmlform.engine.node

/**
 * 节点树的一个结点。
 *
 * **`Node` 上没有 `children`**（M1）：容器才持有子节点，拿 `Node` 的人不该假设它能往下走 ——
 * 否则每个渲染器都要先判「有没有子节点」再决定语义，而那是 `ContainerNode` 已经表达过的事实。
 *
 * [id] 是**结构化路径**（N-5）：`0` / `0.1` / `0.1.2`，按树位置生成，同一份 XML 解析两次得到的 id 必然相同。
 * 它只用于诊断与列表 key 的骨架，**不参与数据查找** —— 数据靠路径，不靠节点身份。
 */
abstract class Node(val id: String) {

    /** 调试与诊断用；渲染不分发到这里（渲染按类型 `when`，不按名字）。 */
    override fun toString(): String = "Node($id)"
}

/**
 * 有子节点的结点。
 *
 * 名字不叫 `LeafNode`（M1 已否决那套继承）：`Column` / `Row` / `Box` / `If` / `Foreach`
 * 都是容器，没有「叶子基类」这种东西可抽。
 */
abstract class ContainerNode(id: String, val children: List<Node>) : Node(id) {

    val isEmpty: Boolean get() = children.isEmpty()
}
