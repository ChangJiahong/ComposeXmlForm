package cn.changjiahong.composexmlform

import cn.changjiahong.composexmlform.api.ComponentModule
import cn.changjiahong.composexmlform.api.install
import cn.changjiahong.composexmlform.components.TextNodeRenderer
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.TextNode
import cn.changjiahong.composexmlform.node.ValAttr
import cn.changjiahong.composexmlform.node.valAttr
import cn.changjiahong.composexmlform.renderer.NodeRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 注册体系的五个动词：`leaf` / `container` / `replace` / `repaint` / `install`。
 *
 * 断言的落点是**报错时机**（撞名、覆盖不存在的标签、重复安装），
 * 以及 `version` 的单调性 —— 后者是「界面已组合后再注册能生效」的唯一依据。
 */
class RegistryTest {

    // ---------------- 撞名与覆盖 ----------------

    @Test
    fun leafRejectsExistingTag() {
        val registry = ComposeXmlForm.createRegistry()

        val error = assertFailsWith<XmlUiException> {
            registry.leaf("Text", ::TagChipNode) { }
        }

        assertEquals("Text", error.tag)
        assertTrue(error.message!!.contains("已注册"), error.message)
        assertTrue(error.message!!.contains("replace"), error.message)
    }

    @Test
    fun replaceRequiresExistingTag() {
        val registry = ComposeXmlForm.createRegistry()

        val error = assertFailsWith<XmlUiException> {
            registry.replace("Chip", ::TagChipNode) { }
        }

        assertEquals("Chip", error.tag)
        assertTrue(error.message!!.contains("尚未注册"), error.message)
        assertTrue(error.message!!.contains("leaf"), error.message)
    }

    @Test
    fun replaceOverridesBuiltIn() {
        val registry = ComposeXmlForm.createRegistry {
            replace("Text", ::TagChipNode) { }
        }

        val root = registry.parse("""<Text label="a"/>""").root
        assertTrue(root is TagChipNode, "replace 后 <Text> 应造出新节点")
    }

    // ---------------- 组件包 ----------------

    @Test
    fun moduleInstallsAndDoubleInstallIsDetected() {
        val module = ComponentModule { it.leaf("Chip", ::TagChipNode) { } }
        val registry = ComposeXmlForm.createRegistry { install(module) }

        val root = registry.parse("""<Column><Chip label="a"/></Column>""").root as ColumnNode
        assertTrue(root.children.single() is TagChipNode)

        // 同一个包装第二次：撞名而不是静默去重 —— 重复依赖能当场暴露
        assertFailsWith<XmlUiException> { registry.install(module) }
    }

    // ---------------- repaint ----------------

    @Test
    fun repaintReplacesRendererOnly() {
        val registry = ComposeXmlForm.createRegistry()
        val custom = TextNodeRenderer()

        registry.repaint<TextNode>(custom)

        val node = registry.parse("""<Text value="a"/>""").root
        assertTrue(custom === registry.renderers.rendererFor(node))
    }

    // ---------------- 渲染期局部容错（R10） ----------------

    /**
     * 只注册节点、不注册渲染器：`rendererFor` 照旧抛（装配期暴露问题），
     * `rendererOrNull` 返回 null —— 渲染侧据此降级为占位，而不是让整棵树挂掉。
     *
     * 「渲染器自己抛错」这一条**测不了**：Compose 编译器禁止 `try/catch` 包裹
     * composable 调用，所以那类异常无法在组合期被收住（见 REFACTOR.md R10 的实测结论）。
     */
    @Test
    fun missingRendererDegradesInsteadOfThrowing() {
        val registry = ComposeXmlForm.createRegistry {
            nodes.register("Orphan") { TagChipNode(it) }
        }
        val node = registry.parse("<Orphan/>").root

        assertNull(registry.renderers.rendererOrNull(node))
        assertFailsWith<XmlUiException> { registry.renderers.rendererFor(node) }
    }

    // ---------------- version ----------------

    @Test
    fun versionAdvancesOnEveryMutation() {
        val registry = ComposeXmlForm.createRegistry()
        var last = registry.version

        fun assertAdvanced(step: String) {
            assertTrue(registry.version > last, "$step 之后 version 应自增（当前 ${registry.version}）")
            last = registry.version
        }

        registry.leaf("Chip", ::TagChipNode) { }
        assertAdvanced("leaf")

        registry.container("Card", ::TagCardNode) { }
        assertAdvanced("container")

        registry.replace("Chip", ::TagChipNode) { }
        assertAdvanced("replace")

        registry.repaint<TagChipNode>(NodeRenderer<TagChipNode> { })
        assertAdvanced("repaint")

        registry.unregister("Chip")
        assertAdvanced("unregister")

        // 底层逃生舱也要被覆盖：直接操作 nodes 表同样算一次变更
        registry.nodes.register("Raw") { TagChipNode(it) }
        assertAdvanced("nodes.register")

        registry.renderers.register(TagChipNode::class, NodeRenderer<TagChipNode> { })
        assertAdvanced("renderers.register")
    }
}

/** 只带一个可选属性，用来验证注册链路（不关心渲染内容）。 */
private class TagChipNode(attrs: Attrs = Attrs.Empty) : Node(attrs.id, attrs.line) {
    val label: ValAttr<String> = attrs.valOf("label") ?: valAttr("")
}

private class TagCardNode(attrs: Attrs = Attrs.Empty, children: List<Node> = emptyList()) :
    cn.changjiahong.composexmlform.node.LayoutNode(children, attrs.id, attrs.line)
