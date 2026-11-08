package cn.changjiahong.composexmlform

import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.AttrValue
import cn.changjiahong.composexmlform.node.Dimension
import cn.changjiahong.composexmlform.node.IfNode
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.TextFieldNode
import cn.changjiahong.composexmlform.node.TextNode
import cn.changjiahong.composexmlform.node.ValAttr
import cn.changjiahong.composexmlform.node.valAttr
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.renderer.readIn
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 属性值：类型由 `AttrValue<T>` 决定，转换规则由 T 自动推导。
 * XML 里只写裸值（`"3"`、`"14"`、`"true"`），不写单位。
 */
class AttrValueTest {

    private val registry = ComposeXmlForm.createRegistry()

    private fun runtime(
        xml: String,
        data: Map<String, Any?>,
        registry: EngineRegistry = this.registry,
    ) = DynamicLayoutRuntime(xml, BindableData.of(data), emptyMap(), Actions.Empty, registry)

    private fun literal(attr: AttrValue<*>?): Any? = (attr as? ValAttr<*>)?.constant

    // ---------------- 字面量 ----------------

    @Test
    fun literalIsConvertedByDeclaredType() {
        val text = ComposeXmlForm.parse(
            """<Text value="你好" maxLines="3" fontSize="14" color="#FF5722"/>""",
            registry,
        ) as TextNode

        assertEquals(3, literal(text.maxLines))
        assertEquals(14f, literal(text.fontSize))
        assertEquals("你好", literal(text.value))
    }

    @Test
    fun unitSuffixIsRejected() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Text value="hi" fontSize="14sp"/>""", registry)
        }
        assertEquals("fontSize", error.attribute)
        assertTrue(error.message!!.contains("14sp"), error.message)
        assertTrue(error.message!!.contains("单位"), error.message)
    }

    @Test
    fun invalidLiteralReportsTagAttributeAndLine() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("<Column>\n  <Text value=\"hi\" maxLines=\"三\"/>\n</Column>", registry)
        }
        assertEquals("Text", error.tag)
        assertEquals("maxLines", error.attribute)
        assertEquals(2, error.line)
        assertTrue(error.message!!.contains("期望整数"), error.message)
    }

    @Test
    fun invalidDimensionReportsUnitHint() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Text value="hi" width="abc"/>""", registry)
        }
        assertEquals("width", error.attribute)
        assertTrue(error.message!!.contains("dp"), error.message)
    }

    // ---------------- 表达式 ----------------

    @Test
    fun boundValueIsCoercedAtRenderTime() {
        val rt = runtime("""<Text value="hi" maxLines="{{count}}" fontSize="{{size}}"/>""", mapOf("count" to "5", "size" to 18))
        val text = rt.layout.root as TextNode

        assertEquals(5, text.maxLines!!.readIn(rt.rootOwner))
        assertEquals(18f, text.fontSize!!.readIn(rt.rootOwner))
    }

    @Test
    fun boundValueWithWrongTypeReportsSite() {
        val rt = runtime("""<Text value="hi" maxLines="{{count}}"/>""", mapOf("count" to "很多"))
        val text = rt.layout.root as TextNode

        val error = assertFailsWith<XmlUiException> { text.maxLines!!.readIn(rt.rootOwner) }
        assertEquals("Text", error.tag)
        assertEquals("maxLines", error.attribute)
        assertTrue(error.message!!.contains("无法转换为整数"), error.message)
    }

    @Test
    fun boundDimensionKeepsFillSemantics() {
        val rt = runtime("""<Text value="hi" width="{{w}}"/>""", mapOf("w" to "fill"))
        val text = rt.layout.root as TextNode

        assertEquals(Dimension.Fill, text.style.width!!.readIn(rt.rootOwner))
    }

    @Test
    fun literalDimensionSupportsFillAndFixed() {
        val fill = ComposeXmlForm.parse("""<Text value="hi" width="fill"/>""", registry) as TextNode
        val fixed = ComposeXmlForm.parse("""<Text value="hi" width="120"/>""", registry) as TextNode

        assertEquals(Dimension.Fill, literal(fill.style.width))
        assertEquals(Dimension.Fixed(120f), literal(fixed.style.width))
    }

    // ---------------- 对象类型 ----------------

    @Test
    fun objectAttributeRejectsLiteral() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<PersonView value="张三"/>""", personRegistry())
        }
        assertEquals("value", error.attribute)
        assertTrue(error.message!!.contains("无法转换为 Person"), error.message)
    }

    @Test
    fun objectAttributeAcceptsMatchingRuntimeType() {
        val person = Person("张三")
        val rt = runtime("""<PersonView value="{{person}}"/>""", mapOf("person" to person), personRegistry())
        val node = rt.layout.root as PersonNode

        assertSame(person, node.person.readIn(rt.rootOwner))
    }

    @Test
    fun objectAttributeRejectsMismatchedRuntimeType() {
        val rt = runtime("""<PersonView value="{{person}}"/>""", mapOf("person" to 42), personRegistry())
        val node = rt.layout.root as PersonNode

        val error = assertFailsWith<XmlUiException> { node.person.readIn(rt.rootOwner) }
        assertTrue(error.message!!.contains("Int"), error.message)
        assertTrue(error.message!!.contains("Person"), error.message)
    }

    @Test
    fun objectAttributeRequiresBinding() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<PersonView value="张三"/>""", personRegistry())
        }
        assertTrue(error.message!!.contains("{{path}}"), error.message)
    }

    // ---------------- 程序化构造（不经过 XML） ----------------

    @Test
    fun nodeCanBeBuiltWithoutXml() {
        val node = TextNode(Attrs.literal("value" to "你好", "fontSize" to 14f, "maxLines" to 2))

        assertEquals("你好", literal(node.value))
        assertEquals(14f, literal(node.fontSize))
        assertEquals(2, literal(node.maxLines))
        // id 由节点自己生成，不依赖解析器
        assertTrue(node.id.startsWith("auto_"), node.id)
    }

    @Test
    fun nodeBuiltWithoutAttributesFallsBackToDefaults() {
        // 只给 id：其余属性全走默认值，没有任何必需属性
        val node = TextNode()

        assertEquals("", literal(node.value))
        assertNull(node.fontSize)
        assertNull(node.color)
    }

    // ---------------- 结构性约束 ----------------

    @Test
    fun twoWayBindingRejectsLiteral() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<TextField value="固定值"/>""", registry)
        }
        assertEquals("value", error.attribute)
        assertTrue(error.message!!.contains("必须是绑定表达式"), error.message)
    }

    @Test
    fun unsupportedAttributeListsDeclaredOnes() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Text value="hi" fontSzie="20"/>""", registry)
        }
        assertEquals("fontSzie", error.attribute)
        // 白名单由工厂实际读取的属性推导，报错里仍然能列出这个标签支持什么
        assertTrue(error.message!!.contains("fontSize"), error.message)
        assertTrue(error.message!!.contains("maxLines"), error.message)
    }
    // ---------------- 缺省属性：缺不是错 ----------------

    @Test
    fun missingAttributesFallBackToNodeDefaults() {
        val text = ComposeXmlForm.parse("<Text/>", registry) as TextNode

        assertEquals("", literal(text.value))
        assertNull(text.fontSize)
        assertNull(text.color)
        assertNull(text.maxLines)
    }

    @Test
    fun textFieldWithoutBindingIsUncontrolled() {
        val field = ComposeXmlForm.parse("""<TextField label="名字"/>""", registry) as TextFieldNode

        // 没有 value 属性 = 没有绑定，渲染器会退化成局部 state
        assertNull(field.value)
        assertEquals("名字", literal(field.label))
    }

    @Test
    fun ifWithoutTestDefaultsToFalse() {
        val node = ComposeXmlForm.parse(
            """<If><Then><Text value="a"/></Then></If>""",
            registry,
        ) as IfNode

        assertEquals(false, literal(node.test))
    }
}

private class Person(val name: String)

private class PersonNode(attrs: Attrs) : Node(attrs.id, attrs.line) {
    val person: ValAttr<Person> = attrs.valOf("value") ?: attrs.fail("缺少必需属性 'value'", "value")
}

private fun personRegistry(): EngineRegistry = ComposeXmlForm.createRegistry {
    leaf("PersonView", ::PersonNode) { }
}
