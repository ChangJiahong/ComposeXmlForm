package cn.changjiahong.composexmlform.parser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.renderer.NodeRenderer
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

    /** 注册代数：任何增删都自增（见 `NodeRegistry.version`）。 */
    var version: Int by mutableIntStateOf(0)
        private set

    fun <T : Node> register(nodeClass: KClass<T>, renderer: NodeRenderer<T>) {
        renderers[nodeClass] = renderer
        resolved.clear()
        version++
    }

    fun unregister(nodeClass: KClass<*>) {
        renderers.remove(nodeClass)
        resolved.clear()
        version++
    }

    fun has(nodeClass: KClass<*>): Boolean = renderers.containsKey(nodeClass)

    fun registeredTypes(): Set<KClass<*>> = renderers.keys.toSet()

    /**
     * 查表，但**没有渲染器时返回 null 而不是抛错**。
     *
     * 这是 R10 局部降级的判定依据：`RenderNode` 拿到 null 就渲染占位并上报，
     * 不会让整棵树因为「注册了节点忘了注册渲染器」而挂掉。`rendererFor` 保留抛错语义，
     * 给「必须在装配期暴露问题」的调用方（测试、CI 校验）用。
     */
    fun rendererOrNull(node: Node): NodeRenderer<Node>? =
        runCatching { rendererFor(node) }.getOrNull()

    fun rendererFor(node: Node): NodeRenderer<Node> {
        val type = node::class
        resolved[type]?.let { return cast(it) }

        val hit = renderers[type]
            ?: renderers.entries.lastOrNull { it.key.isInstance(node) }?.value
            ?: throw XmlUiException(
                "没有为节点类型 ${type.simpleName} 注册渲染器。" +
                    "已注册: ${renderers.keys.mapNotNull { it.simpleName }.sorted().joinToString(", ")}",
                line = node.line,
                code = Code.MissingRenderer,
            )
        resolved[type] = hit
        return cast(hit)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(renderer: NodeRenderer<*>): NodeRenderer<Node> = renderer as NodeRenderer<Node>
}
