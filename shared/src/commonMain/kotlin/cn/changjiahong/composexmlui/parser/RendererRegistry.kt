package cn.changjiahong.composexmlui.parser

import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import kotlin.reflect.KClass

/**
 * 渲染器注册表：**节点类型 → 怎么画**。
 *
 * 按 `KClass` 而不是按标签查找，因此「继承内置节点、只换渲染方式」是可行的：
 * `MyTextNode : TextNode` 注册自己的渲染器后，`<Text>` 造出的 MyTextNode 会用新渲染器；
 * 不注册则自动回退到 `TextNode` 的渲染器。
 *
 * 查找规则（三条，按顺序）：
 * 1. 精确类型命中；
 * 2. 否则在已注册类型里找第一个 `isInstance` 命中的（多重命中时**后注册的优先**，
 *    因为 `LinkedHashMap` 按插入顺序，取最后一个）；
 * 3. 都没有 → 报「没有为某个类型注册渲染器」。
 *
 * 解析结果按类型缓存，渲染时是一次 map 命中，不重复扫描。
 */
class RendererRegistry {

    private val renderers = LinkedHashMap<KClass<*>, NodeRenderer<*>>()
    private val resolved = HashMap<KClass<*>, NodeRenderer<*>>()

    fun <T : Node> register(nodeClass: KClass<T>, renderer: NodeRenderer<T>) {
        renderers[nodeClass] = renderer
        resolved.clear()
    }

    fun unregister(nodeClass: KClass<*>) {
        renderers.remove(nodeClass)
        resolved.clear()
    }

    fun has(nodeClass: KClass<*>): Boolean = renderers.containsKey(nodeClass)

    fun registeredTypes(): Set<KClass<*>> = renderers.keys.toSet()

    fun rendererFor(node: Node): NodeRenderer<Node> {
        val type = node::class
        resolved[type]?.let { return cast(it) }

        val hit = renderers[type]
            ?: renderers.entries.lastOrNull { it.key.isInstance(node) }?.value
            ?: throw XmlUiException(
                "没有为节点类型 ${type.simpleName} 注册渲染器。" +
                    "已注册: ${renderers.keys.mapNotNull { it.simpleName }.sorted().joinToString(", ")}",
                line = node.line,
            )
        resolved[type] = hit
        return cast(hit)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(renderer: NodeRenderer<*>): NodeRenderer<Node> = renderer as NodeRenderer<Node>
}
