package cn.changjiahong.composexmlui.controls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.ForeachNode
import cn.changjiahong.composexmlui.node.IfNode
import cn.changjiahong.composexmlui.renderer.LocalStoreOwner
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import cn.changjiahong.composexmlui.renderer.current
import cn.changjiahong.composexmlui.store.ObservableData
import cn.changjiahong.composexmlui.store.ObservableList
import cn.changjiahong.composexmlui.store.ObservableMap
import cn.changjiahong.composexmlui.store.ScopeStore
import cn.changjiahong.composexmlui.store.StoreOwner

/**
 * If：条件分支。
 * 条件为真渲染 Then 的 children，否则渲染 Else 的 children；分支沿用当前 Owner。
 *
 * 第一版采用「显示 / 移除」语义：分支切换后，被移除分支的临时 Compose 状态不保留。
 */
class IfNodeRenderer : NodeRenderer<IfNode>() {

    @Composable
    override fun Render(node: IfNode) {
        val matched = node.test.current() ?: false
        val branch = if (matched) node.thenBranch else node.elseBranch
        branch?.children?.forEach { child -> RenderChild(child) }
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
 */
class ForeachNodeRenderer : NodeRenderer<ForeachNode>() {

    @Composable
    override fun Render(node: ForeachNode) {
        val owner = LocalStoreOwner.current
        val source = node.items.current()
        val list = source as? ObservableList ?: run {
            if (source != null) {
                throw XmlUiException(
                    "items 必须是列表，实际为 ${source::class.simpleName}",
                    tag = "Foreach",
                    attribute = "items",
                    line = node.line,
                )
            }
            return
        }

        checkDuplicateKeys(node, list)

        for (index in 0 until list.size) {
            val element = list.get(index)
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
                    node.children.forEach { child -> RenderChild(child) }
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
        val rawKey = if (keyAttribute != null && element is ObservableMap) {
            element.get(keyAttribute)
        } else {
            null
        }
        val identity = if (rawKey != null) ObservableData.asString(rawKey) else "#$index"
        return "$prefix:$identity"
    }

    private fun checkDuplicateKeys(node: ForeachNode, list: ObservableList) {
        if (node.keyAttribute == null) return
        val seen = HashSet<String>()
        for (index in 0 until list.size) {
            val key = stableKey(node, list.get(index), index)
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
