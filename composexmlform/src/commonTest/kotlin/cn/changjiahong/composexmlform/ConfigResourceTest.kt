package cn.changjiahong.composexmlform

import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.Severity
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.ForeachNode
import cn.changjiahong.composexmlform.node.SelectNode
import cn.changjiahong.composexmlform.node.UnknownNode
import cn.changjiahong.composexmlform.parser.ParseMode
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `config` 的定位：**解析期资源**，不是运行时命名空间。
 *
 * 这条边界一旦松掉，`Foreach` 就会把元素写回穿到 config 上（config 里的元素是普通 Map，
 * 没有 `MutableState`，写穿不会报错也不会生效）；拼错的 `{{config.region}}` 也会静默空白。
 */
class ConfigResourceTest {

    private val registry = ComposeXmlForm.createRegistry()

    private val config = mapOf(
        "regions" to listOf(
            mapOf("label" to "北京", "value" to "BJ"),
            mapOf("label" to "上海", "value" to "SH"),
        ),
        "count" to 3,
        "bad" to "很多",
    )

    private fun runtime(xml: String) = DynamicLayoutRuntime(
        xml = xml,
        data = BindableData.of(emptyMap()),
        config = config,
        actions = Actions.Empty,
        registry = registry,
    )

    @Test
    fun configValueBecomesConstantAtParseTime() {
        val runtime = runtime("""<Column><Select options="{{config.regions}}"/></Column>""")
        val select = (runtime.layout.root as ColumnNode).children.single() as SelectNode

        // 属性是常量，不是绑定：运行时不再需要 config
        assertNull(select.options.path, "config 属性应在解析期定型成常量")
        val options = select.options.constant as List<*>
        assertEquals(2, options.size)
        assertEquals("BJ", (options.first() as Map<*, *>)["value"])

        // 业务数据里根本没有 config 这个键
        assertNull(runtime.data.get("config"))
        assertNull(runtime.data.get("config.regions"))
    }

    @Test
    fun configValueIsReadableFromAnyScope() {
        // 常量属性在任何作用域下都返回同一份，不依赖 env
        val runtime = runtime("""<Column><Select options="{{config.regions}}"/></Column>""")
        val select = (runtime.layout.root as ColumnNode).children.single() as SelectNode
        val nested = runtime.rootOwner.createChild(mapOf("x" to 1))
        assertEquals(select.options.read(runtime.rootOwner), select.options.read(nested))
    }

    @Test
    fun configInWritableSlotFailsAtParseTime() {
        val error = assertFailsWith<XmlUiException> {
            runtime("""<TextField value="{{config.count}}"/>""")
        }
        assertTrue(error.message!!.contains("只读"), error.message)
        assertTrue(error.message!!.contains("value"), error.message)
    }

    @Test
    fun missingConfigKeyListsAvailableKeys() {
        val error = assertFailsWith<XmlUiException> {
            runtime("""<Text value="{{config.region}}"/>""")
        }
        assertTrue(error.message!!.contains("config 里没有"), error.message)
        // 拼错时把可用键列出来，否则调用方只能猜
        assertTrue(error.message!!.contains("regions"), error.message)
        assertTrue(error.message!!.contains("count"), error.message)
    }

    @Test
    fun configTypeMismatchIsReportedAtParseTime() {
        val error = assertFailsWith<XmlUiException> {
            // bad 是字符串 "很多"，而 Text 的 maxLines 声明为 ValAttr<Int>
            runtime("""<Text value="hi" maxLines="{{config.bad}}"/>""")
        }
        assertEquals("maxLines", error.attribute)
        assertEquals(Code.TypeMismatch, error.code)
    }

    @Test
    fun configNumericValueAdaptsToDeclaredType() {
        // count 是数字 3 → maxLines 是 Int，解析期就定型
        val runtime = runtime("""<Text value="hi" maxLines="{{config.count}}"/>""")
        val text = runtime.layout.root as cn.changjiahong.composexmlform.node.TextNode
        assertEquals(3, text.maxLines!!.constant)
    }

    @Test
    fun configListIsNotWritable() {
        val runtime = runtime(
            """<Foreach items="{{config.regions}}" item="item"><TextField value="{{item.value}}"/></Foreach>""",
        )
        val foreach = runtime.layout.root as ForeachNode
        val list = foreach.items.constant as List<*>
        assertEquals(2, list.size)

        // 元素是普通 Map（只读常量）。写回必须报错，而不是静默丢进一个副本
        val element = list.first() as Map<*, *>
        val childOwner = runtime.rootOwner.createChild(mapOf("item" to element))
        val error = assertFailsWith<XmlUiException> {
            childOwner.write(ValuePath.parse("item.value"), "SH")
        }
        assertTrue(error.message!!.contains("只读常量"), error.message)
        // 原值未被修改
        assertEquals("BJ", (config["regions"] as List<*>).first().let { (it as Map<*, *>)["value"] })
    }
}

/**
 * 解析期诊断：`Strict` 收集完全部再抛，`Tolerant` 降级渲染。
 */
class ParseDiagnosticsTest {

    private val registry = ComposeXmlForm.createRegistry()

    @Test
    fun strictCollectsAllErrorsBeforeThrowing() {
        val error = assertFailsWith<XmlUiException> {
            registry.parse(
                """
                <Column>
                  <Text value="a" fontSzie="20"/>
                  <Nope/>
                  <Text value="b" maxLines="三"/>
                </Column>
                """.trimIndent(),
            )
        }
        // 一次拿到三条，而不是修一个报一个
        assertEquals(3, error.diagnostics.size, error.message)
        assertEquals(
            setOf(Code.UnknownAttribute, Code.UnknownTag, Code.TypeMismatch),
            error.diagnostics.map { it.code }.toSet(),
        )
        assertTrue(error.message!!.contains("共 3 处"), error.message)
    }

    @Test
    fun tolerantDowngradesUnknownTagAndAttribute() {
        val result = registry.parse(
            """
            <Column>
              <Text value="a" fontSzie="20"/>
              <Nope/>
            </Column>
            """.trimIndent(),
            mode = ParseMode.Tolerant,
        )

        assertTrue(!result.hasError, result.diagnostics.toString())
        assertEquals(2, result.warnings.size)
        assertEquals(
            setOf(Code.UnknownAttribute, Code.UnknownTag),
            result.warnings.map { it.code }.toSet(),
        )

        val column = result.root as ColumnNode
        assertEquals(2, column.children.size)
        val placeholder = column.children[1]
        assertIs<UnknownNode>(placeholder)
        assertEquals("Nope", placeholder.tagName)
    }

    @Test
    fun tolerantStillReportsStructuralErrors() {
        // 叶子节点带子元素是结构错误，不能降级：行为已经不对了
        val result = registry.parse(
            """<Text value="hi"><Text value="child"/></Text>""",
            mode = ParseMode.Tolerant,
        )
        assertTrue(result.hasError)
        assertEquals(Code.Structure, result.errors.single().code)
    }

    @Test
    fun rootProtocolAttributesAreNotTreatedAsUnknown() {
        val result = registry.parse("""<Column version="2" minEngine="1"><Text value="a"/></Column>""")
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
    }

    @Test
    fun layoutTooNewIsReported() {
        val error = assertFailsWith<XmlUiException> {
            registry.parse("""<Column minEngine="99"><Text value="a"/></Column>""")
        }
        assertEquals(Code.LayoutTooNew, error.code)
        assertTrue(error.message!!.contains("99"), error.message)
    }

    @Test
    fun tolerantStillRendersWhenLayoutIsTooNew() {
        val result = registry.parse(
            """<Column minEngine="99"><Text value="a"/></Column>""",
            mode = ParseMode.Tolerant,
        )
        // 版本不够仍能画出认得的那些节点，问题通过诊断回传而不是崩溃
        assertTrue(result.hasError)
        assertEquals(Code.LayoutTooNew, result.errors.single().code)
        assertEquals(1, (result.root as ColumnNode).children.size)
    }

    @Test
    fun diagnosticsCarryLineNumbers() {
        val result = registry.parse(
            """
            <Column>
              <Text value="a" fontSzie="20"/>
            </Column>
            """.trimIndent(),
            mode = ParseMode.Tolerant,
        )
        val warning = result.warnings.single()
        assertEquals("Text", warning.tag)
        assertEquals("fontSzie", warning.attribute)
        assertEquals(2, warning.line)
        assertEquals(Severity.Warning, warning.severity)
    }

    @Test
    fun successPathHasNoDiagnostics() {
        val result = registry.parse("""<Column><Text value="a"/></Column>""")
        assertTrue(result.diagnostics.isEmpty())
        assertTrue(!result.hasError)
    }
}
