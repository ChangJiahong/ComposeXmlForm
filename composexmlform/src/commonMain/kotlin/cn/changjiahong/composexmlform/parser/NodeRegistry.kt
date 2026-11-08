package cn.changjiahong.composexmlform.parser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.Node

/**
 * 节点注册表：XML 标签 → 怎么造出节点。
 *
 * 独立成一张表（而不是和渲染器绑在一起）的原因：**只做解析与校验、不做渲染**的场景
 * 只需要这张表。`ComposeXmlForm.parse()`、IDE 预览、CI 静态检查都可以在没有 Compose
 * 渲染器的环境里跑。
 *
 * 分两个入口，因为**建造顺序不同**：
 * - [register]：叶子节点先建节点，子元素随后由解析器检查并拒绝（因此不浪费一次子树解析）；
 * - [registerLayout]：容器节点先建子节点，再把 `List<Node>` 传进构造。
 *
 * 注册项是覆盖式的：后注册的同名标签覆盖先注册的，内置组件因此也可以被替换。
 */
class NodeRegistry {

    private val leafCreators = LinkedHashMap<String, (Attrs) -> Node>()
    private val layoutCreators = LinkedHashMap<String, (Attrs, List<Node>) -> Node>()

    /**
     * 注册代数：任何增删都自增。
     *
     * 是 `mutableIntStateOf` 而不是普通 `var`，因为读它的地方是 `DynamicLayout` 的
     * `remember` key —— 必须在 composition 里可订阅，注册变化才能立即重建运行时。
     */
    var version: Int by mutableIntStateOf(0)
        private set

    /** 注册叶子组件：构造时只需要属性。 */
    fun register(tag: String, create: (Attrs) -> Node) {
        layoutCreators.remove(tag)
        leafCreators[tag] = create
        version++
    }

    /** 注册容器组件：构造时拿到已经建好的子节点。 */
    fun registerLayout(tag: String, create: (Attrs, List<Node>) -> Node) {
        leafCreators.remove(tag)
        layoutCreators[tag] = create
        version++
    }

    fun unregister(tag: String) {
        leafCreators.remove(tag)
        layoutCreators.remove(tag)
        version++
    }

    fun has(tag: String): Boolean = tag in leafCreators || tag in layoutCreators

    fun tags(): Set<String> = LinkedHashSet<String>().apply {
        addAll(leafCreators.keys)
        addAll(layoutCreators.keys)
    }

    internal fun isLayout(tag: String): Boolean = tag in layoutCreators

    internal fun createLeaf(tag: String, attrs: Attrs): Node =
        leafCreators[tag]?.invoke(attrs) ?: notRegistered(tag, attrs.line)

    internal fun createLayout(tag: String, attrs: Attrs, children: List<Node>): Node =
        layoutCreators[tag]?.invoke(attrs, children) ?: notRegistered(tag, attrs.line)

    private fun notRegistered(tag: String, line: Int): Nothing = throw XmlUiException(
        "未注册的组件标签 <$tag>。已注册标签: ${tags().sorted().joinToString(", ")}",
        tag = tag,
        line = line,
        code = Code.UnknownTag,
    )
}
