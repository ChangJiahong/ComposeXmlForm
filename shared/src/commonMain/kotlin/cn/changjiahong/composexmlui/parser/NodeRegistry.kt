package cn.changjiahong.composexmlui.parser

import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node

/**
 * 节点注册表：XML 标签 → 怎么造出节点。
 *
 * 独立成一张表（而不是和渲染器绑在一起）的原因：**只做解析与校验、不做渲染**的场景
 * 只需要这张表。`ComposeXmlUi.parse()`、IDE 预览、CI 静态检查都可以在没有 Compose
 * 渲染器的环境里跑。
 *
 * 注册项是覆盖式的：后注册的同名标签覆盖先注册的，内置组件因此也可以被替换。
 */
class NodeRegistry {

    private val creators = LinkedHashMap<String, (NodeContext) -> Node>()

    fun register(tag: String, create: (NodeContext) -> Node) {
        creators[tag] = create
    }

    fun unregister(tag: String) {
        creators.remove(tag)
    }

    fun has(tag: String): Boolean = creators.containsKey(tag)

    fun tags(): Set<String> = creators.keys.toSet()

    internal fun create(tag: String, context: NodeContext): Node =
        creators[tag]?.invoke(context) ?: throw XmlUiException(
            "未注册的组件标签 <$tag>。已注册标签: ${creators.keys.sorted().joinToString(", ")}",
            tag = tag,
            line = context.line,
        )
}
