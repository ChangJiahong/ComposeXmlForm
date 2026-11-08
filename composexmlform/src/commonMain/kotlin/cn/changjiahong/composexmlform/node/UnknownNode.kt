package cn.changjiahong.composexmlform.node

/**
 * `Tolerant` 模式下未知标签的占位节点。
 *
 * 它让「服务器下发了一个本客户端不认识的组件」变成「界面上少一块、其余照常可用」，
 * 而不是整页崩掉。渲染器（`components/UnknownNodeRenderer`）只画一个灰底框 + 标签名。
 *
 * 刻意留在 `node` 包：它是个普通节点，不依赖渲染框架（守住 R6 的包边界）。
 * 等 R10 的 `LocalRenderFallback` 落地后，这个节点会成为默认 fallback 的实现。
 */
class UnknownNode(
    /** XML 里写的标签名，原样保留 —— 排查问题时这是唯一有效信息。 */
    val tagName: String,
    line: Int = 0,
) : Node(id = null, line = line) {

    override fun toString(): String = "<$tagName #$id>"
}
