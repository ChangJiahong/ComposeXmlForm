package cn.changjiahong.composexmlform

import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import cn.changjiahong.composexmlform.validation.FormState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 表单校验（R11）：规则从 XML 属性来、在解析期收集，`errors` 是独立命名空间。
 *
 * 三个断言落点：① 规则确实随解析结果带出来（不靠遍历树）；② 错误文案与清除；
 * ③ **错误不进 `data`** —— 否则 `snapshot()` 会把错误一起提交给服务器。
 */
class ValidationTest {

    private val registry = ComposeXmlForm.createRegistry()

    private fun runtime(xml: String, data: Map<String, Any?>): DynamicLayoutRuntime =
        DynamicLayoutRuntime(xml, BindableData.of(data), emptyMap(), Actions.Empty, registry)

    private fun formOf(rt: DynamicLayoutRuntime): FormState =
        FormState(rt.data).also { it.attach(rt.validationPlan) }

    @Test
    fun requiredFieldProducesError() {
        val rt = runtime(
            """<Column><TextField label="姓名" value="{{name}}" required="true"/></Column>""",
            mapOf("name" to "  "),
        )
        val form = formOf(rt)

        assertEquals(1, rt.validationPlan.rules.size)
        assertFalse(form.validate())
        assertEquals("姓名 不能为空", form.errorOf("name"))
    }

    @Test
    fun errorDisappearsWhenRuleIsSatisfied() {
        val rt = runtime(
            """<Column><TextField label="昵称" value="{{nick}}" minLength="3" maxLength="6"/></Column>""",
            mapOf("nick" to "ab"),
        )
        val form = formOf(rt)

        assertFalse(form.validate())
        assertEquals("昵称 至少 3 个字符", form.errorOf("nick"))

        rt.data.set("nick", "abcdefg")
        assertFalse(form.validate())
        assertEquals("昵称 最多 6 个字符", form.errorOf("nick"))

        rt.data.set("nick", "abcd")
        assertTrue(form.validate())
        assertNull(form.errorOf("nick"))
    }

    @Test
    fun errorsNeverEnterData() {
        val rt = runtime(
            """<Column><TextField label="邮箱" value="{{email}}" required="true"/></Column>""",
            mapOf("email" to ""),
        )
        val form = formOf(rt)

        form.validate()

        val snapshot = rt.data.snapshot()
        assertEquals(setOf("email"), snapshot.keys)
        assertFalse(snapshot.values.any { it == "邮箱 不能为空" })
        assertNull(snapshot["errors"])
    }

    /** 规则挂在绑定路径上：没有 `value` 的输入框没有字段身份，因此不产生规则。 */
    @Test
    fun uncontrolledFieldProducesNoRule() {
        val rt = runtime(
            """<Column><TextField label="备注" required="true"/></Column>""",
            emptyMap(),
        )

        assertTrue(rt.validationPlan.isEmpty)
    }

    @Test
    fun invalidPatternFailsAtParseTime() {
        val error = assertFailsWith<XmlUiException> {
            runtime(
                """<Column><TextField value="{{phone}}" pattern="[0-9"/></Column>""",
                emptyMap(),
            )
        }

        assertEquals("pattern", error.attribute)
        assertTrue(error.message!!.contains("正则"), error.message)
    }
}
