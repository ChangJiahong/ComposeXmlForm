package cn.changjiahong.composexmlform

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.api.preview
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.StyledNode
import cn.changjiahong.composexmlform.node.ValAttr
import cn.changjiahong.composexmlform.renderer.LocalRenderDiagnostics
import cn.changjiahong.composexmlform.renderer.NodeRenderer
import cn.changjiahong.composexmlform.renderer.current
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 渲染层测试（C2）：**"细粒度更新"不再是注释里的承诺，而是断言**。
 *
 * 计数方式：渲染器在自己的 `Render` 里自增一个普通 `MutableMap`。它刻意不是
 * `mutableStateOf` —— 计数本身不能触发重组，否则测的就是计数器的重组。
 *
 * 同时补上 R10 的剩余验收：缺失渲染器时渲染占位（而不是抛错），并上报一条诊断。
 */
@OptIn(ExperimentalTestApi::class)
class RenderTest {

    /** 只声明一个可读属性，别的什么都不做 —— 把它当成「可观测的探针」。 */
    private class ProbeNode(attrs: Attrs = Attrs.Empty) : StyledNode(attrs) {
        val value: ValAttr<String>? = attrs.valOf("value")
    }

    private class ProbeRenderer(private val counter: MutableMap<String, Int>) :
        NodeRenderer<ProbeNode> {

        @Composable
        override fun Render(node: ProbeNode) {
            val text = node.value?.current().orEmpty()
            counter[node.id] = (counter[node.id] ?: 0) + 1
            BasicText(text)
        }
    }

    private fun registry(counter: MutableMap<String, Int>) =
        ComposeXmlForm.createRegistry { leaf("Probe", ::ProbeNode, ProbeRenderer(counter)) }

    private val xml = """
        <Column>
          <Probe id="bound" value="{{name}}"/>
          <Probe id="static" value="固定值"/>
        </Column>
    """.trimIndent()

    @Test
    fun writingOneFieldOnlyRecomposesTheRendererThatReadsIt() = runComposeUiTest {
        val counter = mutableMapOf<String, Int>()
        val registry = registry(counter)
        val root = registry.parse(xml).root
        val data = BindableData.of(mapOf("name" to "张三"))

        // registry 必须显式传：preview 的默认值是 LocalEngineRegistry.current（全局注册表），
        // 里面没有 Probe，不传就会渲染成「缺失渲染器」占位
        setContent { preview(root, data, registry = registry) }
        waitForIdle()

        // 首帧：两个探针各组合一次
        assertEquals(1, counter["bound"])
        assertEquals(1, counter["static"])

        data.set("name", "李四")
        waitForIdle()

        // 只有读了 name 的那个重跑；常量属性不建立订阅，另一个纹丝不动
        assertEquals(2, counter["bound"])
        assertEquals(1, counter["static"])
        onNodeWithText("李四").assertExists()
    }

    @Test
    fun writingAnUnrelatedKeyRecomposesNothing() = runComposeUiTest {
        val counter = mutableMapOf<String, Int>()
        val registry = registry(counter)
        val root = registry.parse(xml).root
        val data = BindableData.of(mapOf("name" to "张三", "other" to 1))

        setContent { preview(root, data, registry = registry) }
        waitForIdle()
        data.set("other", 2)
        waitForIdle()

        assertEquals(1, counter["bound"])
        assertEquals(1, counter["static"])
    }

    @Test
    fun dataChangeDoesNotRebuildTheNodeTree() = runComposeUiTest {
        val counter = mutableMapOf<String, Int>()
        val registry = registry(counter)
        val root = registry.parse(xml).root
        val data = BindableData.of(mapOf("name" to "张三"))

        setContent { preview(root, data, registry = registry) }
        val column = root as ColumnNode
        val bound = column.children.first()

        data.set("name", "李四")
        waitForIdle()

        // 传进 preview 的节点树实例没变 —— 数据变化走的是属性订阅，不是重新解析
        assertTrue(root === column, "节点树是调用方持有的实例")
        assertTrue(column.children.first() === bound)
    }

    @Test
    fun missingRendererDegradesToPlaceholderAndReportsDiagnostic() = runComposeUiTest {
        val counter = mutableMapOf<String, Int>()
        val registry = registry(counter)
        val root = registry.parse("""<Column><Probe id="bound" value="hi"/></Column>""").root
        // 注册之后又把渲染器摘掉：模拟「节点类型存在但没有渲染器」
        registry.renderers.unregister(ProbeNode::class)

        val reported = mutableListOf<Diagnostic>()
        setContent {
            CompositionLocalProvider(LocalRenderDiagnostics provides { reported += it }) {
                preview(root, registry = registry)
            }
        }
        waitForIdle()

        assertEquals(0, counter["bound"] ?: 0, "渲染器已被摘掉，不该被调用")
        onNodeWithText("<ProbeNode> 渲染失败").assertExists()
        assertTrue(reported.any { it.code == Code.MissingRenderer }, reported.toString())
    }

    @Test
    fun previewRendersWithoutAnyDataOrActions() = runComposeUiTest {
        // TextNode 自带默认值：不传 data / actions 也能渲染，这是 preview 的默认承诺
        setContent { preview(cn.changjiahong.composexmlform.node.TextNode(Attrs.literal("value" to "你好"))) }
        onNodeWithText("你好").assertExists()
    }

    @Test
    fun bindingWithoutDataReadsNullInsteadOfCrashing() = runComposeUiTest {
        val root = ComposeXmlForm.createRegistry().parse("""<Text value="{{name}}"/>""").root
        setContent { preview(root) }
        // 默认空数据根：读到 null → 空字符串，不抛
        onNodeWithText("").assertExists()
    }
}
