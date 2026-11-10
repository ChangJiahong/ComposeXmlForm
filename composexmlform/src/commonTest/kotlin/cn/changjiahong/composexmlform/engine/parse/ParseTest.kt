package cn.changjiahong.composexmlform.engine.parse

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.env.EnvSchema
import cn.changjiahong.composexmlform.engine.node.Attr
import cn.changjiahong.composexmlform.engine.node.AttrDef
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.node.ChildRule
import cn.changjiahong.composexmlform.engine.node.ContainerNode
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.node.NodeType
import cn.changjiahong.composexmlform.engine.node.NodeTypes
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.dataObjectOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 四阶段解析：结构 → 作用域 → 定型 → 校验。
 *
 * 每一条对应一个「使用者真的会写错」的场景：标签拼错、属性拼错、少写必填、
 * 括号不配对、把只读对象当可写目标、在默认档位里改数据。
 */
class ParseTest {

    // ── ① 结构 ───────────────────────────────────────────────

    @Test
    fun malformedXmlStopsWithNullRoot() {
        val result = parseOk("<Column><Text></Column>")

        assertNull(result.root, "结构错误之后没有「继续解析」这回事")
        assertEquals(Code.XmlMalformed, result.diagnostics.single().code)
    }

    @Test
    fun elementTextIsRejected() {
        val result = parseOk("<Column>hello</Column>")
        assertEquals(Code.XmlMalformed, result.diagnostics.single().code)
        assertTrue(result.format().contains("文本内容"))
    }

    @Test
    fun duplicateAttributeIsRejected() {
        val result = parseOk("""<Column><Text text="a" text="b"/></Column>""")
        assertEquals(Code.XmlMalformed, result.diagnostics.single().code)
    }

    @Test
    fun attributeValueMustBeQuoted() {
        val result = parseOk("""<Column><Text text=a/></Column>""")
        assertEquals(Code.XmlMalformed, result.diagnostics.single().code)
    }

    // ── ② 作用域 ─────────────────────────────────────────────

    @Test
    fun structuralAttributeIntroducesLocalForChildren() {
        val result = parseOk(
            """<Foreach source="{{data.list}}" item="row"><Text text="{{row.name}}"/></Foreach>""",
        )
        assertFalse(result.hasErrors, result.format())
    }

    @Test
    fun localIsInvisibleOutsideItsElement() {
        // row 出了 Foreach 既不是局部、也不像某个全局对象名 → 按 data 的字段兜底（E-1）
        val result = parseOk("""<Column><Text text="{{row.name}}"/></Column>""")
        assertFalse(result.hasErrors, result.format())
    }

    @Test
    fun misspelledGlobalIsReported() {
        val result = parseOk("""<Column><Text text="{{dta.user}}"/></Column>""")

        val diagnostic = result.diagnostics.single { it.code == Code.UnknownGlobal }
        assertEquals("你是不是想写『data』", diagnostic.hint)
    }

    @Test
    fun structuralAttributeTakesBareIdentifierOnly() {
        val result = parseOk(
            """<Foreach source="{{data.list}}" item="{{x}}"><Text text="a"/></Foreach>""",
        )
        assertTrue(result.diagnostics.any { it.code == Code.InvalidAttributeValue })
    }

    @Test
    fun structuralAttributeCannotShadowGlobalObject() {
        val result = parseOk(
            """<Foreach source="{{data.list}}" item="data"><Text text="a"/></Foreach>""",
        )
        val diagnostic = result.diagnostics.single { it.code == Code.InvalidAttributeValue }
        assertTrue(diagnostic.message.contains("保留字"))
    }

    // ── ③ 定型 ───────────────────────────────────────────────

    @Test
    fun scriptErrorReportsColumnInsideAttribute() {
        val xml = """<Text text="{ 1 + }"/>"""
        val result = parseOk(xml)

        val diagnostic = result.diagnostics.single { it.code == Code.ScriptSyntax }
        // `<Text text="` 占 12 列，属性值从第 13 列开始
        assertTrue(diagnostic.span!!.column > 13, "要报在属性值内部，实际：${diagnostic.format(xml)}")
        assertEquals(1, diagnostic.span.line)
    }

    @Test
    fun unclosedBracesAreReported() {
        val result = parseOk("""<Text text="{{data.x"/>""")
        assertEquals(Code.MalformedBraces, result.diagnostics.single().code)
    }

    @Test
    fun invalidPathIsReported() {
        val result = parseOk("""<Text text="{{data..x}}"/>""")
        assertEquals(Code.InvalidPath, result.diagnostics.single().code)
    }

    @Test
    fun bindingToGlobalGetsAbsoluteRoot() {
        val result = parseOk("""<Text text="{{data.user.name}}"/>""")
        assertFalse(result.hasErrors, result.format())

        val slot = assertIs<TestLeaf>(result.root).slots["text"]!!
        assertEquals("data", slot.boundPath!!.root, "首段是全局对象就标成绝对路径（快路径）")
    }

    @Test
    fun relativeBindingKeepsNoRoot() {
        val result = parseOk("""<Text text="{{user.name}}"/>""")
        assertFalse(result.hasErrors, result.format())

        val slot = assertIs<TestLeaf>(result.root).slots["text"]!!
        assertNull(slot.boundPath!!.root, "相对路径交给运行期兜底到 data")
    }

    // ── ③ 档位 ───────────────────────────────────────────────

    @Test
    fun expressionsOnlyRejectsAssignment() {
        val result = parseOk("""<Text text="{ data.x = 1 }"/>""")
        assertTrue(result.diagnostics.any { it.code == Code.ScriptNotAllowed })
    }

    @Test
    fun fullModeAllowsAssignment() {
        val result = parseOk("""<Text text="{ data.x = 1 }"/>""", ScriptMode.Full)
        assertFalse(result.hasErrors, result.format())
    }

    @Test
    fun noScriptRejectsBothBracketForms() {
        val code = parseOk("""<Text text="{ 1 }"/>""", ScriptMode.NoScript)
        assertTrue(code.diagnostics.any { it.code == Code.ScriptNotAllowed })

        val lambda = parseOk("""<Input value="{{data.x}}" onClick="@{ f() }"/>""", ScriptMode.NoScript)
        assertTrue(lambda.diagnostics.any { it.code == Code.ScriptNotAllowed })
    }

    @Test
    fun expressionsOnlyRejectsWriteInLambda() {
        val result = parseOk("""<Input value="{{data.x}}" onClick="@{ data.x = 1 }"/>""")
        assertTrue(result.diagnostics.any { it.code == Code.ScriptNotAllowed })
    }

    // ── ④ 可写性与槽位 ───────────────────────────────────────

    @Test
    fun readonlyNamespaceInWritableSlotIsReported() {
        val result = parseOk("""<Input value="{{config.x}}" onClick="@{ f() }"/>""")

        val diagnostic = result.diagnostics.single { it.code == Code.NotWritableNamespace }
        assertEquals("value", diagnostic.attribute)
    }

    @Test
    fun codeBlockInWritableSlotIsReported() {
        // create 只读 value 不读 onClick，写法矩阵仍然要生效
        val result = parseOk("""<Input value="{ data.x }" onClick="@{ f() }"/>""")
        assertTrue(result.diagnostics.any { it.code == Code.ReadOnlySlot })
    }

    @Test
    fun literalInFunctionSlotIsReported() {
        val result = parseOk("""<Input value="{{data.x}}" onClick="login"/>""")
        assertTrue(result.diagnostics.any { it.code == Code.InvalidAttributeValue })
    }

    // ── ④ 属性表 ─────────────────────────────────────────────

    @Test
    fun unknownAttributeAndMissingRequiredBothReported() {
        val result = parseOk("""<Column><Text txt="a"/></Column>""")

        assertEquals(Code.UnknownAttribute, result.diagnostics.single { it.code == Code.UnknownAttribute }.code)
        assertEquals(
            Code.MissingRequiredAttribute,
            result.diagnostics.single { it.code == Code.MissingRequiredAttribute }.code,
        )
    }

    @Test
    fun diagnosticsCollectAcrossTheWholeTree() {
        val xml = """
            <Column>
                <Nope/>
                <Text txt="x"/>
                <Input value="{ 1 + }" onClick="login"/>
            </Column>
        """.trimIndent()
        val result = parseOk(xml)

        assertTrue(result.diagnostics.size >= 4, "一次解析要把问题全报出来：\n${result.format(xml)}")
    }

    // ── ④ 子节点 ─────────────────────────────────────────────

    @Test
    fun childRuleNoneRejectsChildren() {
        val result = parseOk("""<Text text="a"><Then/></Text>""")
        assertTrue(result.diagnostics.any { it.code == Code.ChildNotAllowed })
    }

    @Test
    fun childRuleAtLeastOneRequiresChildren() {
        val result = parseOk("""<Foreach source="{{data.list}}" item="row"/>""")
        assertTrue(result.diagnostics.any { it.code == Code.ChildRequired })
    }

    @Test
    fun childRuleOnlyChecksTags() {
        val result = parseOk("""<If><Column/></If>""")
        val diagnostic = result.diagnostics.single { it.code == Code.ChildTagMismatch }
        assertTrue(diagnostic.message.contains("Column"))
    }

    // ── 构造失败 ─────────────────────────────────────────────

    @Test
    fun createFailureBecomesDiagnostic() {
        val types = NodeTypes.of(
            NodeType("Boom") { _, _, _ -> error("宿主的锅") },
        )
        val result = parse("<Boom/>", schema(), types)

        assertEquals(Code.NodeConstruction, result.diagnostics.single().code)
        assertNull(result.root, "构造失败的节点不入树")
    }

    // ── 结构化 id ────────────────────────────────────────────

    @Test
    fun structuralIdsFollowPosition() {
        val result = parseOk(
            """
            <Column>
                <Text text="a"/>
                <Column>
                    <Text text="b"/>
                </Column>
            </Column>
            """.trimIndent(),
        )
        assertFalse(result.hasErrors, result.format())

        val root = assertIs<TestBranch>(result.root)
        assertEquals("0", root.id)
        assertEquals("0.0", root.children[0].id)
        assertEquals("0.1", root.children[1].id)
        assertEquals("0.1.0", assertIs<TestBranch>(root.children[1]).children[0].id)
    }

    @Test
    fun idsAreStableAcrossParses() {
        val xml = """<Column><Text text="a"/><Text text="b"/></Column>"""
        val first = idsOf(parseOk(xml).root!!)
        val second = idsOf(parseOk(xml).root!!)
        assertEquals(first, second)
    }

    // ── 测试用节点类型 ───────────────────────────────────────

    private class TestLeaf(
        id: String,
        val tag: String,
        val slots: Map<String, AttrSlot<*>>,
    ) : Node(id)

    private class TestBranch(
        id: String,
        val tag: String,
        children: List<Node>,
    ) : ContainerNode(id, children)

    private val textDef: AttrDef<String> = Attr.string("text", required = true)
    private val valueDef: AttrDef<String> = Attr.varString("value")
    private val clickDef: AttrDef<cn.changjiahong.composexmlform.engine.value.Function> = Attr.func("onClick")

    private fun types(): NodeTypes = NodeTypes.of(
        NodeType("Text", listOf(textDef), ChildRule.None) { id, set, _ ->
            TestLeaf(id, "Text", mapOf("text" to set.attr(textDef)))
        },
        NodeType("Column", emptyList(), ChildRule.Any) { id, _, children ->
            TestBranch(id, "Column", children)
        },
        NodeType(
            "Foreach",
            listOf(
                Attr.string("source"),
                Attr.string("item", structural = true),
                Attr.string("index", structural = true),
            ),
            ChildRule.AtLeastOne,
        ) { id, _, children ->
            TestBranch(id, "Foreach", children)
        },
        NodeType("If", emptyList(), ChildRule.Only(setOf("Then", "Else"))) { id, _, children ->
            TestBranch(id, "If", children)
        },
        NodeType("Then", emptyList(), ChildRule.Any) { id, _, children ->
            TestBranch(id, "Then", children)
        },
        NodeType("Else", emptyList(), ChildRule.Any) { id, _, children ->
            TestBranch(id, "Else", children)
        },
        // 刻意不读任何属性：验证「没被读过也要校验」
        NodeType("Input", listOf(valueDef, clickDef)) { id, _, _ ->
            TestLeaf(id, "Input", emptyMap())
        },
    )

    private fun schema(): EnvSchema = EnvSchema.from(
        mapOf(
            "data" to dataObjectOf("list" to emptyList<Any>()),
            "config" to DataObject.frozen(emptyMap()),
            "action" to DataObject.frozen(emptyMap()),
        ),
    )

    private fun parseOk(xml: String, mode: ScriptMode = ScriptMode.ExpressionsOnly): ParseResult =
        parse(xml, schema(), types(), mode)

    private fun idsOf(node: Node): List<String> = buildList {
        add(node.id)
        if (node is ContainerNode) node.children.forEach { addAll(idsOf(it)) }
    }
}
