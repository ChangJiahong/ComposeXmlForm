package cn.changjiahong.composexmlform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 节点树结构快照。
 *
 * 现有用例几乎都在断言「属性值 / 数据绑定」，没有一条断言**树长什么样**。
 * R6 会把 7 个节点从「主构造 + 次构造」改成 attrs-primary 单构造，
 * 属性读漏、读串（`color = attrs.valOf("fontSize")`）时原有断言抓不到，
 * 这个文件就是那张网。
 *
 * 打印规则见 [dumpTree]：不带 `auto_*` id、不打印非 String 的值
 * （批次 0 会把 `Dp` / `Color` 换成 `Float` / `String`，打印值会让快照因类型变化大面积失配）。
 *
 * 加样例的规则：**覆盖一个此前没覆盖的节点类型 / 属性槽位**，而不是覆盖一个业务场景。
 * 期望值手写，不要用 `dumpTree(...)` 的结果回填 —— 那样等于没有断言。
 */
class NodeTreeSnapshotTest {

    private val registry = ComposeXmlForm.createRegistry()

    private fun dump(xml: String): String = dumpTree(ComposeXmlForm.parse(xml, registry)).trimEnd()

    private fun assertDump(xml: String, expected: String) {
        assertEquals(expected.trimIndent(), dump(xml))
    }

    // ---------------- 叶子 ----------------

    @Test
    fun textWithLiteralAttributes() = assertDump(
        """<Text value="hi"/>""",
        """
        Text [value="hi"]
        """,
    )

    @Test
    fun textWithBoundAndTypedAttributes() = assertDump(
        """<Text value="{{username}}" color="#FF5722" maxLines="3" fontSize="14" fontWeight="bold" textAlign="center"/>""",
        """
        Text [value, fontSize, color="#FF5722", maxLines, fontWeight="bold", textAlign="center"]
        """,
    )

    @Test
    fun buttonCarriesActionAsStructuralField() = assertDump(
        """<Button text="提交" action="submit()" enabled="false" padding="8"/>""",
        """
        Button [text="提交", action=submit(), enabled, padding]
        """,
    )

    @Test
    fun textFieldBindingSlotIsMarkedAsWritable() = assertDump(
        """<TextField label="姓名" placeholder="请输入" value="{{username}}"/>""",
        """
        TextField [value=*, label="姓名", placeholder="请输入"]
        """,
    )

    @Test
    fun textFieldWithoutAnyAttributeHasNoSlots() = assertDump(
        "<TextField/>",
        """
        TextField
        """,
    )

    @Test
    fun selectKeepsOptionsAndWritableValue() = assertDump(
        """<Select label="城市" options="{{regions}}" value="{{region}}"/>""",
        """
        Select [options, value=*, label="城市"]
        """,
    )

    // ---------------- 容器 ----------------

    @Test
    fun columnKeepsOwnAttributesAndChildren() = assertDump(
        """<Column scroll="true" spacing="12" horizontalAlignment="center" width="fill"><Text value="a"/></Column>""",
        """
        Column [scroll, spacing, horizontalAlignment="center", width]
          Text [value="a"]
        """,
    )

    @Test
    fun rowKeepsVerticalAlignment() = assertDump(
        """<Row verticalAlignment="center" paddingHorizontal="6"><Text value="x"/></Row>""",
        """
        Row [verticalAlignment="center", paddingHorizontal]
          Text [value="x"]
        """,
    )

    @Test
    fun boxKeepsContentAlignment() = assertDump(
        """<Box contentAlignment="center"><Text value="x"/></Box>""",
        """
        Box [contentAlignment="center"]
          Text [value="x"]
        """,
    )

    @Test
    fun styledAttributesLiveOnEveryStyledNode() = assertDump(
        """<Column backgroundColor="#F3F4F6"><Text value="a" height="wrap" paddingVertical="4"/></Column>""",
        """
        Column [backgroundColor="#F3F4F6"]
          Text [value="a", height, paddingVertical]
        """,
    )

    // ---------------- 控制节点 ----------------

    @Test
    fun ifKeepsBothBranchesInOrder() = assertDump(
        """<If test="{{isLogin}}"><Then><Text value="a"/></Then><Else><Text value="b"/></Else></If>""",
        """
        If [test]
          Branch [branch="Then"]
            Text [value="a"]
          Branch [branch="Else"]
            Text [value="b"]
        """,
    )

    @Test
    fun foreachKeepsStructuralNames() = assertDump(
        """<Foreach items="{{contacts}}" item="item" index="i" key="id"><Text value="{{item.name}}"/></Foreach>""",
        """
        Foreach [items, item="item", index="i", key="id"]
          Text [value]
        """,
    )

    @Test
    fun declaredIdIsVisibleWhileAutoIdIsNormalized() = assertDump(
        """<Column id="main"><Text value="a"/><Text value="b"/></Column>""",
        """
        Column#main
          Text [value="a"]
          Text [value="b"]
        """,
    )
}
