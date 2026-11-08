package cn.changjiahong.composexmlform

import cn.changjiahong.composexmlform.binding.ValuePath
import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.ButtonNode
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.ForeachNode
import cn.changjiahong.composexmlform.node.IfNode
import cn.changjiahong.composexmlform.node.TextNode
import cn.changjiahong.composexmlform.renderer.readIn
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import cn.changjiahong.composexmlform.store.ObservableList
import cn.changjiahong.composexmlform.store.ObservableMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineTest {

    private val registry = ComposeXmlForm.createRegistry()

    private val sampleData = mapOf(
        "username" to "张三",
        "region" to "BJ",
        "isLogin" to true,
        "user" to mapOf("name" to "张三", "age" to 30),
        "contacts" to listOf(
            mapOf("id" to 1, "name" to "李雷", "phone" to "13800000001"),
            mapOf("id" to 2, "name" to "韩梅梅", "phone" to "13800000002"),
        ),
    )

    private val sampleConfig = mapOf(
        "regions" to listOf(
            mapOf("label" to "北京", "value" to "BJ"),
            mapOf("label" to "上海", "value" to "SH"),
        ),
    )

    private fun runtime(
        xml: String,
        data: Map<String, Any?> = sampleData,
        config: Map<String, Any?> = sampleConfig,
        actions: Actions = Actions.Empty,
    ) = DynamicLayoutRuntime(xml, BindableData.of(data), config, actions, registry)

    // ---------------- 解析 ----------------

    @Test
    fun parseBuildsTypedNodeTree() {
        val root = ComposeXmlForm.parse(
            """<Column><Text value="{{username}}"/></Column>""",
            registry,
        )
        assertTrue(root is ColumnNode)
        val text = root.children.single() as TextNode
        assertEquals("username", text.value.path!!.raw)
        assertEquals("Text", text.value.site.tag)
        assertEquals("value", text.value.site.attribute)
    }

    @Test
    fun unknownTagReportsTagName() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Column><Unknown/></Column>""", registry)
        }
        assertTrue(error.message!!.contains("Unknown"), error.message)
        assertEquals("Unknown", error.tag)
    }

    @Test
    fun unknownAttributeIsRejected() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Text value="hi" fontSzie="20"/>""", registry)
        }
        assertTrue(error.message!!.contains("fontSzie"), error.message)
        assertEquals("fontSzie", error.attribute)
    }

    @Test
    fun missingAttributeFallsBackToDefault() {
        val root = ComposeXmlForm.parse("<Column>\n  <Text/>\n</Column>", registry) as ColumnNode
        val text = root.children.single() as TextNode

        // 缺属性不是错误：Text 的 value 默认是空字符串
        assertEquals("", text.value.constant)
    }

    @Test
    fun unclosedTagReportsLine() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("<Column>\n  <Text value=\"hi\"/>\n", registry)
        }
        assertTrue(error.message!!.contains("Column"), error.message)
    }

    @Test
    fun mixedInterpolationIsRejected() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Text value="你好 {{username}}"/>""", registry)
        }
        assertTrue(error.message!!.contains("{{path}}"), error.message)
    }

    @Test
    fun textContentIsRejected() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("<Column>hello</Column>", registry)
        }
        assertTrue(error.message!!.contains("属性"), error.message)
    }

    @Test
    fun leafNodeRejectsChildren() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Text value="hi"><Text value="child"/></Text>""", registry)
        }
        assertTrue(error.message!!.contains("子节点"), error.message)
    }

    @Test
    fun actionWithArgumentsIsRejected() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<Button text="提交" action="submit(1)"/>""", registry)
        }
        assertTrue(error.message!!.contains("不支持参数"), error.message)
    }

    @Test
    fun unregisteredActionIsRejectedAtRuntime() {
        val error = assertFailsWith<XmlUiException> {
            runtime("""<Column><Button text="提交" action="submit()"/></Column>""")
        }
        assertTrue(error.message!!.contains("未注册"), error.message)
    }

    @Test
    fun ifRequiresThenBranch() {
        val error = assertFailsWith<XmlUiException> {
            ComposeXmlForm.parse("""<If test="{{isLogin}}"><Else><Text value="a"/></Else></If>""", registry)
        }
        assertTrue(error.message!!.contains("Then"), error.message)
    }

    // ---------------- 绑定与作用域 ----------------

    @Test
    fun readsNestedAndScopedPaths() {
        val rt = runtime("""<Column><Text value="{{user.name}}"/></Column>""")
        val text = (rt.layout.root as ColumnNode).children.single() as TextNode
        assertEquals("张三", text.value.readIn(rt.rootOwner))
    }

    @Test
    fun dataReadsAndWritesRootData() {
        val rt = runtime("""<Column><Text value="{{username}}"/></Column>""")
        rt.data.set("username", "李四")
        assertEquals("李四", rt.data.get("username"))
        assertEquals("李四", rt.data.snapshot()["username"])
    }

    @Test
    fun innerScopeShadowsOuterData() {
        val rt = runtime("""<Column><Text value="{{name}}"/></Column>""", data = mapOf("name" to "outer"))
        val child = rt.rootOwner.createChild(mapOf("name" to "inner"))
        assertEquals("inner", child.readString(cn.changjiahong.composexmlform.binding.BindingExpression.Path(ValuePath.parse("name"))))
        assertEquals("outer", rt.rootOwner.readString(cn.changjiahong.composexmlform.binding.BindingExpression.Path(ValuePath.parse("name"))))
    }

    // ---------------- Foreach 写回（高风险点） ----------------

    @Test
    fun foreachItemWriteBackUpdatesOriginalList() {
        val rt = runtime(
            """<Foreach items="{{contacts}}" item="item" key="id"><Text value="{{item.name}}"/></Foreach>""",
        )
        val foreach = rt.layout.root as ForeachNode
        val list = rt.data.get("contacts") as ObservableList
        val element = list.get(0)

        val childOwner = rt.rootOwner.createChild(mapOf(foreach.itemName to element))
        childOwner.write(ValuePath.parse("item.name"), "韩梅梅")

        val first = (rt.data.snapshot()["contacts"] as List<*>).first() as Map<*, *>
        assertEquals("韩梅梅", first["name"])
    }

    @Test
    fun itemIndexScopeIsAvailable() {
        val rt = runtime(
            """<Foreach items="{{contacts}}" item="item" index="i"><Text value="{{i}}"/></Foreach>""",
        )
        val foreach = rt.layout.root as ForeachNode
        assertEquals("i", foreach.indexName)
        val childOwner = rt.rootOwner.createChild(mapOf("i" to 1))
        assertEquals("1", childOwner.readString(cn.changjiahong.composexmlform.binding.BindingExpression.Path(ValuePath.parse("i"))))
    }

    @Test
    fun foreachReadsKeyAttribute() {
        val rt = runtime(
            """<Foreach items="{{contacts}}" item="item" key="id"><Text value="{{item.name}}"/></Foreach>""",
        )
        val foreach = rt.layout.root as ForeachNode
        assertEquals("id", foreach.keyAttribute)
        val list = rt.data.get("contacts") as ObservableList
        assertEquals(2, list.size)
        assertEquals(1, (list.get(0) as ObservableMap).get("id"))
    }

    // ---------------- 数据容器 ----------------

    @Test
    fun observableMapKeepsKeyOrderAndTypes() {
        val map = newObservableRoot()
        map.put("count", 3)
        map.put("flag", true)
        assertEquals(listOf("name", "count", "flag"), map.keys)
        assertEquals(3, map.get("count"))
        assertEquals(true, map.get("flag"))
    }

    private fun newObservableRoot(): ObservableMap {
        val rt = runtime("""<Column><Text value="{{username}}"/></Column>""", data = mapOf("name" to "n"))
        return (rt.rootOwner.store as cn.changjiahong.composexmlform.store.RootStore).dataRoot()
    }

    @Test
    fun ifNodeKeepsBranches() {
        val rt = runtime(
            """<If test="{{isLogin}}"><Then><Text value="a"/></Then><Else><Text value="b"/></Else></If>""",
        )
        val node = rt.layout.root as IfNode
        assertEquals(1, node.thenBranch.children.size)
        assertEquals(1, node.elseBranch!!.children.size)
    }

    @Test
    fun buttonCarriesParsedAction() {
        val rt = runtime(
            """<Column><Button text="提交" action="submit()"/></Column>""",
            actions = Actions.of(mapOf("submit" to {})),
        )
        val button = (rt.layout.root as ColumnNode).children.single() as ButtonNode
        assertEquals("submit", button.action!!.constant!!.name)
    }
}
