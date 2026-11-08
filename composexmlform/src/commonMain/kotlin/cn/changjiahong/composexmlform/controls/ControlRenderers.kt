package cn.changjiahong.composexmlform.controls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.ForeachNode
import cn.changjiahong.composexmlform.node.IfNode
import cn.changjiahong.composexmlform.renderer.LocalStoreOwner
import cn.changjiahong.composexmlform.renderer.NodeRenderer
import cn.changjiahong.composexmlform.renderer.RenderNode
import cn.changjiahong.composexmlform.renderer.current
import cn.changjiahong.composexmlform.renderer.get
import cn.changjiahong.composexmlform.store.ObservableData
import cn.changjiahong.composexmlform.store.ObservableList
import cn.changjiahong.composexmlform.store.ObservableMap
import cn.changjiahong.composexmlform.store.ScopeStore
import cn.changjiahong.composexmlform.store.StoreOwner

/**
 * If：条件分支。
 * 条件为真渲染 Then 的 children，否则渲染 Else 的 children；分支沿用当前 Owner。
 *
 * 第一版采用「显示 / 移除」语义：分支切换后，被移除分支的临时 Compose 状态不保留。
 */
class IfNodeRenderer : NodeRenderer<IfNode> {

    @Composable
    override fun Render(node: IfNode) {
        val matched = node.test.get(false)
        val branch = if (matched) node.thenBranch else node.elseBranch
        branch?.children?.forEach { child -> RenderNode(child) }
    }
}

/**
 * Foreach：列表循环 + 独立子作用域。
 *
 * 关键实现点：
 * 1. 每个元素创建一个子 StoreOwner，把 `item` 变量绑定到「列表中的那个元素对象」本身，
 *    而不是它的副本 —— 因此 `{{item.name}}` 的写入会直接落到原始列表元素上（数据写回）。
 * 2. 使用 `key` + `remember` 维持元素身份：列表增删 / 排序时组件状态跟随数据项而不是位置。
 * 3. 列表结构由 SnapshotStateList 订阅：只有增删改顺序会触发循环重组。
 *
 * 数据源同时接受 [ObservableList] 与**普通** `List`：`items="{{config.regions}}"` 在解析期
 * 就固化成了普通列表常量，没有 `MutableState`，但它仍然要能被遍历。
 */
class ForeachNodeRenderer : NodeRenderer<ForeachNode> {

    @Composable
    override fun Render(node: ForeachNode) {
        val owner = LocalStoreOwner.current
        val source = node.items.current()
        val elements: List<Any?> = when (source) {
            null -> return
            is ObservableList -> source.toList() // 读结构 → 订阅增删
            is List<*> -> source
            else -> throw XmlUiException(
                "items 必须是列表，实际为 ${source::class.simpleName}",
                tag = "Foreach",
                attribute = "items",
                line = node.line,
            )
        }

        checkDuplicateKeys(node, elements)

        elements.forEachIndexed { index, element ->
            val elementKey = stableKey(node, element, index)
            key(elementKey) {
                val childOwner = remember(elementKey, element) {
                    owner.createChild(scopeVars(node, element, index))
                }
                if (node.indexName != null) {
                    val scope = childOwner.store as? ScopeStore
                    SideEffect { scope?.putVar(node.indexName, index) }
                }
                CompositionLocalProvider(LocalStoreOwner provides childOwner) {
                    node.children.forEach { child -> RenderNode(child) }
                }
            }
        }
    }

    private fun scopeVars(node: ForeachNode, element: Any?, index: Int): Map<String, Any?> {
        val vars = LinkedHashMap<String, Any?>(3)
        vars[node.itemName] = element
        node.indexName?.let { vars[it] = index }
        return vars
    }

    private fun stableKey(node: ForeachNode, element: Any?, index: Int): String {
        val prefix = node.id
        val keyAttribute = node.keyAttribute
        val rawKey = if (keyAttribute == null) {
            null
        } else {
            when (element) {
                is ObservableMap -> element.get(keyAttribute)
                is Map<*, *> -> element[keyAttribute]
                else -> null
            }
        }
        val identity = if (rawKey != null) ObservableData.asString(rawKey) else "#$index"
        return "$prefix:$identity"
    }

    private fun checkDuplicateKeys(node: ForeachNode, elements: List<Any?>) {
        if (node.keyAttribute == null) return
        val seen = HashSet<String>()
        elements.forEachIndexed { index, element ->
            val key = stableKey(node, element, index)
            if (!seen.add(key)) {
                throw XmlUiException(
                    "key='${node.keyAttribute}' 存在重复值（${key.substringAfterLast(':')}），" +
                        "循环项的 key 必须唯一，否则无法稳定复用组件状态",
                    tag = "Foreach",
                    attribute = "key",
                    line = node.line,
                )
            }
        }
    }
}

/** 供自定义容器复用：为子节点提供新的作用域。 */
@Composable
fun WithLocalScope(
    owner: StoreOwner,
    initialVars: Map<String, Any?>,
    content: @Composable () -> Unit,
) {
    val childOwner = remember(owner, initialVars) { owner.createChild(initialVars) }
    CompositionLocalProvider(LocalStoreOwner provides childOwner) {
        content()
    }
}
