package cn.changjiahong.composexmlui.runtime

import cn.changjiahong.composexmlui.api.DynamicLayoutController
import cn.changjiahong.composexmlui.binding.ActionRef
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.ButtonNode
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.store.RootStore
import cn.changjiahong.composexmlui.store.StoreOwner

/** 预处理后的布局：节点树 + 原始 XML（用于缓存键与调试）。 */
class ParsedLayout(val xml: String, val root: Node)

/**
 * 节点树缓存：XML 字符串 + 注册表身份 → 解析结果。
 * 数据变化时只重算绑定，不重新解析 XML。
 */
object LayoutCache {

    private const val MAX_ENTRIES = 32

    private data class Key(val xml: String, val registry: EngineRegistry)

    private val cache = LinkedHashMap<Key, ParsedLayout>()

    fun obtain(xml: String, registry: EngineRegistry): ParsedLayout {
        val key = Key(xml, registry)
        cache[key]?.let { return it }
        val parsed = ParsedLayout(xml, registry.parse(xml))
        cache[key] = parsed
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
        return parsed
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}

/**
 * 一次动态界面实例的运行时。
 *
 * - [layout]：预处理后的节点树（可缓存复用）
 * - [rootOwner]：业务数据与作用域
 * - [actionRegistry]：事件函数
 * - [registry]：组件注册表（通常由引擎共享）
 */
class DynamicLayoutRuntime(
    val xml: String,
    data: Map<String, Any?>,
    config: Map<String, Any?>,
    actions: Map<String, () -> Unit>,
    val registry: EngineRegistry,
) {

    val layout: ParsedLayout = LayoutCache.obtain(xml, registry)

    val rootOwner: StoreOwner = StoreOwner(RootStore(data, config), parent = null)

    val actionRegistry: ActionRegistry = ActionRegistry()

    val controller: DynamicLayoutController = DynamicLayoutController(this)

    var isDisposed: Boolean = false
        private set

    init {
        actionRegistry.replaceAll(actions)
        validateActions(layout.root)
    }

    /** 供 API 层判断能否复用（XML / config 变化时重建）。 */
    fun dataSnapshot(): Map<String, Any?> = rootOwner.store.getData()

    fun dispose() {
        isDisposed = true
        actionRegistry.clear()
    }

    private fun validateActions(root: Node) {
        val references = mutableListOf<Pair<ActionRef, Node>>()
        collectActions(root, references)
        references.forEach { (action, node) ->
            if (!actionRegistry.has(action.name)) {
                throw XmlUiException(
                    "action 引用了未注册的函数 ${action.raw}。请通过 actions 参数注册 '${action.name}'",
                    tag = node.tag,
                    attribute = "action",
                    line = node.line,
                )
            }
        }
    }

    private fun collectActions(node: Node, out: MutableList<Pair<ActionRef, Node>>) {
        if (node is ButtonNode) {
            node.action?.let { out += it to node }
        }
        node.children.forEach { collectActions(it, out) }
    }
}
