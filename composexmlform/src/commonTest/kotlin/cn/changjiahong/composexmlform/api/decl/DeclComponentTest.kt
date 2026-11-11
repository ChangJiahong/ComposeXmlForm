package cn.changjiahong.composexmlform.api.decl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cn.changjiahong.composexmlform.ComposeXmlForm
import cn.changjiahong.composexmlform.api.DynamicLayout
import cn.changjiahong.composexmlform.api.dataOf
import cn.changjiahong.composexmlform.api.getList
import cn.changjiahong.composexmlform.api.getLong
import cn.changjiahong.composexmlform.components.style
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.dataArrayOf
import cn.changjiahong.composexmlform.engine.value.dataObjectOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 组件声明的**全部样板代码**，就下面两块：一个类 + 一个 Composable。
 *
 * 对比旧写法（`ApiTest.customComponentRendersAndWritesBack`）：那里还要一个 `Attr` 对象、
 * 一个 `create` lambda、一个 `NodeRenderer` 实现，属性名写四遍。
 */
private class StarsNode(props: Props) : PropsNode(props) {

    /** 可写：`{{data.rating}}` 时点一下直接回写数据。 */
    var value by props.varLong("value", 0L)

    val max by props.long("max", 5L)

    val style = props.style()
}

@Composable
private fun Stars(node: StarsNode) {
    Row(node.style.modifier()) {
        Text(
            text = "星级 ${node.value} / ${node.max}",
            modifier = Modifier.clickable { node.value = node.value + 1 },
        )
    }
}

private fun starsRegistry() = ComposeXmlForm.createRegistry {
    component("Stars", create = ::StarsNode, render = ::Stars)
}

@OptIn(ExperimentalTestApi::class)
class DeclComponentTest {

    /** 属性表是**构造函数里那几行**跑出来的，不需要另外维护。 */
    @Test
    fun attributesAreCollectedFromTheConstructor() {
        val type = starsRegistry().types["Stars"]!!
        assertEquals(
            listOf(
                "value", "max",
                "width", "height", "padding", "paddingHorizontal", "paddingVertical", "backgroundColor",
            ),
            type.attributes.map { it.name },
        )

        val value = type.attributes.first { it.name == "value" }
        assertTrue(value.writable, "varLong 应该是可写槽位")
        assertFalse(type.attributes.first { it.name == "max" }.writable, "long 应该只读")
    }

    /** 委托读值 + 赋值回写：渲染器里没有 `value()` / `orElse()` / `rememberField()`。 */
    @Test
    fun delegateReadsAndWritesThroughBinding() = runComposeUiTest {
        val data = dataOf(mapOf("rating" to 2L))
        setContent {
            DynamicLayout(
                xml = """<Column><Stars value="{{data.rating}}"/></Column>""",
                data = data,
                registry = starsRegistry(),
            )
        }

        onNodeWithText("星级 2 / 5").assertExists()
        onNodeWithText("星级 2 / 5").performClick()
        waitForIdle()

        assertEquals(3L, data.getLong("rating"))
        onNodeWithText("星级 3 / 5").assertExists()
    }

    /** 没绑定就是非受控：赋值只改这一行的局部 state，且要跨重组留住。 */
    @Test
    fun unboundWritableStaysLocalAcrossRecomposition() = runComposeUiTest {
        val data = dataOf()
        setContent {
            DynamicLayout(
                xml = """<Column><Stars max="3"/></Column>""",
                data = data,
                registry = starsRegistry(),
            )
        }

        onNodeWithText("星级 0 / 3").assertExists()
        onNodeWithText("星级 0 / 3").performClick()
        waitForIdle()

        onNodeWithText("星级 1 / 3").assertExists()
        assertEquals(0L, data.getLong("rating"), "没绑定就不该写 data")
    }

    /** 关键一条：`Foreach` 每行绑定自己的 env，点击只回写那一行。 */
    @Test
    fun eachRowBindsItsOwnEnv() = runComposeUiTest {
        val data = dataOf(
            mapOf(
                "rows" to dataArrayOf(
                    dataObjectOf(mapOf("score" to 1L)),
                    dataObjectOf(mapOf("score" to 4L)),
                ),
            ),
        )
        setContent {
            DynamicLayout(
                xml = """
                    <Column>
                        <Foreach source="{{data.rows}}" item="row">
                            <Stars value="{{row.score}}" max="9"/>
                        </Foreach>
                    </Column>
                """.trimIndent(),
                data = data,
                registry = starsRegistry(),
            )
        }

        onNodeWithText("星级 1 / 9").assertExists()
        onNodeWithText("星级 4 / 9").assertExists()

        onNodeWithText("星级 1 / 9").performClick()
        waitForIdle()

        val rows = data.getList("rows")
        assertEquals(2L, (rows[0] as DataObject).getLong("score"))
        assertEquals(4L, (rows[1] as DataObject).getLong("score"), "第二行不该被第一行的点击带偏")
        onNodeWithText("星级 2 / 9").assertExists()
        onNodeWithText("星级 4 / 9").assertExists()
    }
}
