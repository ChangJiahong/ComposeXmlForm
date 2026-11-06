package cn.changjiahong.composexmlui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.AttrValue
import cn.changjiahong.composexmlui.node.Dimension
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.node.TextNode
import cn.changjiahong.composexmlui.node.ValAttr
import cn.changjiahong.composexmlui.node.valAttr
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.parser.NodeContext
import cn.changjiahong.composexmlui.renderer.get
import cn.changjiahong.composexmlui.runtime.DynamicLayoutRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 属性值：类型由 `AttrValue<T>` 决定，转换规则由 T 自动推导。
 * XML 里只写裸值（`"3"`、`"14"`、`"true"`），不写单位。
 */
class AttrValueTest {

    private val registry = ComposeXmlUi.createRegistry()

    private fun runtime(
        xml: String,
        data: Map<String, Any?>,
        registry: EngineRegistry = this.registry,
    ) = DynamicLayoutRuntime(xml, data, emptyMap(), emptyMap(), registry)

    private fun literal(attr: AttrValue<*>?): Any? = (attr as? ValAttr<*>)?.constant

    // ---------------- 字面量 ----------------

    @Test
    fun literalIsConvertedByDeclaredType() {
        val text = ComposeXmlUi.parse(
            """<Text value="你好" maxLines="3" fontSize="14" color="#FF5722"/>""",
            registry,
        ) as TextNode

        assertEquals(3, literal(text.maxLines))
        assertEquals(14.sp, literal(text.fontSize))
        assertEquals("你好", literal(text.value))
    }

    @Test
    fun unitSuffixIsRejected() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlUi.parse("""<Text value="hi" fontSize="14sp"/>""", registry)
        }
        assertEquals("fontSize", error.attribute)
        assertTrue(error.message!!.contains("14sp"), error.message)
        assertTrue(error.message!!.contains("单位"), error.message)
    }

    @Test
    fun invalidLiteralReportsTagAttributeAndLine() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlUi.parse("<Column>\n  <Text value=\"hi\" maxLines=\"三\"/>\n</Column>", registry)
        }
        assertEquals("Text", error.tag)
        assertEquals("maxLines", error.attribute)
        assertEquals(2, error.line)
        assertTrue(error.message!!.contains("期望整数"), error.message)
    }

    @Test
    fun invalidDimensionReportsUnitHint() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlUi.parse("""<Text value="hi" width="abc"/>""", registry)
        }
        assertEquals("width", error.attribute)
        assertTrue(error.message!!.contains("dp"), error.message)
    }

    // ---------------- 表达式 ----------------

    @Test
    fun boundValueIsCoercedAtRenderTime() {
        val rt = runtime("""<Text value="hi" maxLines="{{count}}" fontSize="{{size}}"/>""", mapOf("count" to "5", "size" to 18))
        val text = rt.layout.root as TextNode

        assertEquals(5, text.maxLines!!.get(rt.rootOwner))
        assertEquals(18.sp, text.fontSize!!.get(rt.rootOwner))
    }

    @Test
    fun boundValueWithWrongTypeReportsSite() {
        val rt = runtime("""<Text value="hi" maxLines="{{count}}"/>""", mapOf("count" to "很多"))
        val text = rt.layout.root as TextNode

        val error = assertFailsWith<XmlUiException> { text.maxLines!!.get(rt.rootOwner) }
        assertEquals("Text", error.tag)
        assertEquals("maxLines", error.attribute)
        assertTrue(error.message!!.contains("无法转换为整数"), error.message)
    }

    @Test
    fun boundDimensionKeepsFillSemantics() {
        val rt = runtime("""<Text value="hi" width="{{w}}"/>""", mapOf("w" to "fill"))
        val text = rt.layout.root as TextNode

        assertEquals(Dimension.Fill, text.width!!.get(rt.rootOwner))
    }

    @Test
    fun literalDimensionSupportsFillAndFixed() {
        val fill = ComposeXmlUi.parse("""<Text value="hi" width="fill"/>""", registry) as TextNode
        val fixed = ComposeXmlUi.parse("""<Text value="hi" width="120"/>""", registry) as TextNode

        assertEquals(Dimension.Fill, literal(fill.width))
        assertEquals(Dimension.Fixed(120.dp), literal(fixed.width))
    }

    // ---------------- 对象类型 ----------------

    @Test
    fun objectAttributeRejectsLiteral() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlUi.parse("""<PersonView value="张三"/>""", personRegistry())
        }
        assertEquals("value", error.attribute)
        assertTrue(error.message!!.contains("无法转换为 Person"), error.message)
    }

    @Test
    fun objectAttributeAcceptsMatchingRuntimeType() {
        val person = Person("张三")
        val rt = runtime("""<PersonView value="{{person}}"/>""", mapOf("person" to person), personRegistry())
        val node = rt.layout.root as PersonNode

        assertSame(person, node.person.get(rt.rootOwner))
    }

    @Test
    fun objectAttributeRejectsMismatchedRuntimeType() {
        val rt = runtime("""<PersonView value="{{person}}"/>""", mapOf("person" to 42), personRegistry())
        val node = rt.layout.root as PersonNode

        val error = assertFailsWith<XmlUiException> { node.person.get(rt.rootOwner) }
        assertTrue(error.message!!.contains("Int"), error.message)
        assertTrue(error.message!!.contains("Person"), error.message)
    }

    @Test
    fun objectAttributeRequiresBinding() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlUi.parse("""<PersonView value="张三"/>""", personRegistry())
        }
        assertTrue(error.message!!.contains("{{path}}"), error.message)
    }

    // ---------------- 程序化构造（不经过 XML） ----------------

    @Test
    fun nodeCanBeBuiltWithoutXml() {
        val node = TextNode(
            value = valAttr("你好"),
            fontSize = valAttr(14.sp),
            maxLines = valAttr(2),
        )

        assertEquals("你好", literal(node.value))
        assertEquals(14.sp, literal(node.fontSize))
        assertEquals(2, literal(node.maxLines))
        // id 由节点自己生成，不依赖解析器
        assertTrue(node.id.startsWith("auto_"), node.id)
    }

    // ---------------- 结构性约束 ----------------

    @Test
    fun twoWayBindingRejectsLiteral() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlUi.parse("""<TextField value="固定值"/>""", registry)
        }
        assertEquals("value", error.attribute)
        assertTrue(error.message!!.contains("必须是绑定表达式"), error.message)
    }

    @Test
    fun unsupportedAttributeListsDeclaredOnes() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlUi.parse("""<Text value="hi" fontSzie="20"/>""", registry)
        }
        assertEquals("fontSzie", error.attribute)
        // 白名单由工厂实际读取的属性推导，报错里仍然能列出这个标签支持什么
        assertTrue(error.message!!.contains("fontSize"), error.message)
        assertTrue(error.message!!.contains("maxLines"), error.message)
    }
}

private class Person(val name: String)

private class PersonNode(context: NodeContext) : Node(context.id, context.line) {
    val person: AttrValue<Person> = context.need("value")
}

private fun personRegistry(): EngineRegistry = ComposeXmlUi.createRegistry().also { registry ->
    registry.register("PersonView", PersonNode::class, ::PersonNode) { }
}
