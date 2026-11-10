package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.node.Attr
import cn.changjiahong.composexmlform.engine.node.AttrDef
import cn.changjiahong.composexmlform.engine.node.AttrRef
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.syntax.parseExpression
import cn.changjiahong.composexmlform.engine.syntax.parseLambda
import cn.changjiahong.composexmlform.engine.value.AttrSite
import cn.changjiahong.composexmlform.engine.value.CoerceException
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Fn
import cn.changjiahong.composexmlform.engine.value.MutableDataObject
import cn.changjiahong.composexmlform.engine.value.Path
import cn.changjiahong.composexmlform.engine.value.dataObjectOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 属性读取的四条路径（`PLAN.md` S7 的表格，实现落在 `eval` 而不是 `node`）。
 *
 * 重点在**边界**：没写 vs 写了收敛失败、绑定到不存在路径、函数槽位的兜底提示 ——
 * 这些是「同一份 XML 在不同数据下表现不同」的主要来源。
 */
class AttrReadTest {

    private fun data(): MutableDataObject = dataObjectOf(
        "name" to "abc",
        "n" to 1L,
        "user" to dataObjectOf("name" to "u1"),
    )

    private fun action(): DataObject = DataObject.frozen(
        mapOf("click" to Fn.noArg { "clicked" }),
    )

    private fun context(data: DataObject = data()): EvalContext {
        val objects = mapOf("data" to data, "action" to action())
        return EvalContext(Env.root(objects))
    }

    // ── 四条路径 ─────────────────────────────────────────────

    @Test
    fun literalGoesThroughTypeAdapter() {
        val ctx = context()
        assertEquals(12L, slot(Attr.int("size"), AttrRef.Literal("12")).read(ctx))
        assertEquals("hi", slot(Attr.string("text"), AttrRef.Literal("hi")).read(ctx))
        assertEquals(true, slot(Attr.bool("bold"), AttrRef.Literal("true")).read(ctx))
    }

    @Test
    fun bindingReadsThroughEnv() {
        val ctx = context()
        assertEquals("abc", slot(Attr.string("text"), AttrRef.Bind(path("data.name"))).read(ctx))
        assertEquals("u1", slot(Attr.string("text"), AttrRef.Bind(path("data.user.name"))).read(ctx))
    }

    @Test
    fun bindingFollowsDataChanges() {
        val data = data()
        val ctx = context(data)
        val slot = slot(Attr.string("text"), AttrRef.Bind(path("data.name")))

        assertEquals("abc", slot.read(ctx))
        data.setMember("name", "xyz")
        assertEquals("xyz", slot.read(ctx), "绑定是活的：数据变了读到的就变")
    }

    @Test
    fun bindingToMissingPathFailsToCoerce() {
        val ctx = context()
        val slot = slot(Attr.string("text"), AttrRef.Bind(path("data.missing")))
        assertFailsWith<CoerceException> { slot.read(ctx) }
    }

    @Test
    fun codeIsEvaluated() {
        val ctx = context()
        val slot = slot(Attr.int("size"), AttrRef.Code(parseExpression("data.n + 1")))
        assertEquals(2L, slot.read(ctx))
    }

    @Test
    fun codeSeesHintForBareFunctionName() {
        // 函数槽位里裸写 `click()` 要兜底到 action.click（V-13）
        val ctx = context()
        val slot = slot(Attr.func("onClick"), AttrRef.Lambda(parseLambda("click()")))

        val function = slot.read(ctx)
        assertEquals("clicked", function.invoke(null, emptyList()))
    }

    @Test
    fun lambdaBuildsFunctionWithoutCallingIt() {
        val data = data()
        val ctx = context(data)
        val slot = slot(Attr.func("onClick"), AttrRef.Lambda(parseLambda("data.n = 5")))

        val function = slot.read(ctx)
        assertEquals(1L, data.member("n"), "读属性不该执行函数")

        function.invoke(null, emptyList())
        assertEquals(5L, data.member("n"))
    }

    @Test
    fun unwrittenSlotUsesDeclaredDefault() {
        val ctx = context()
        assertEquals("", slot(Attr.string("text"), AttrRef.Absent).read(ctx))
        assertEquals(14L, slot(Attr.int("size", default = 14L), AttrRef.Absent).read(ctx))
    }

    // ── 宽松读取 ─────────────────────────────────────────────

    @Test
    fun readOrNullSwallowsCoerceFailure() {
        val ctx = context()
        assertNull(slot(Attr.int("size"), AttrRef.Literal("abc")).readOrNull(ctx))
        assertEquals(0L, slot(Attr.int("size"), AttrRef.Literal("0")).readOrNull(ctx))
    }

    @Test
    fun readOrDefaultFallsBackAfterFailure() {
        val ctx = context()
        val slot = slot(Attr.int("size", default = 14L), AttrRef.Literal("abc"))
        assertEquals(14L, slot.readOrDefault(ctx))
    }

    @Test
    fun readOrDefaultKeepsSuccessfulZero() {
        // 0 是有效值，不能因为它是 0 就当成「没读到」
        val ctx = context()
        assertEquals(0L, slot(Attr.int("size", default = 14L), AttrRef.Literal("0")).readOrDefault(ctx))
    }

    // ── 可写回 ───────────────────────────────────────────────

    @Test
    fun boundPathIsExposedForWriteBack() {
        val ctx = context()
        val writable = slot(Attr.varString("value"), AttrRef.Bind(path("data.name")))
        assertTrue(writable.present)
        assertTrue(writable.isBinding)
        assertEquals("data.name", writable.boundPath!!.display())

        val literal = slot(Attr.string("text"), AttrRef.Literal("x"))
        assertNull(literal.boundPath)
    }

    @Test
    fun repeatedReadIsStateless() {
        val ctx = context()
        val slot = slot(Attr.string("text"), AttrRef.Bind(path("data.name")))
        assertEquals("abc", slot.read(ctx))
        assertEquals("abc", slot.read(ctx))
        assertEquals("text", slot.site.attribute, "slot 不带缓存，也不在读取时改自己")
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private fun <T> slot(def: AttrDef<T>, ref: AttrRef): AttrSlot<T> =
        AttrSlot(def, ref, AttrSite("Text", def.name, Span.None))

    private fun path(raw: String): Path = Path.parse(raw)!!.withRoot(raw.substringBefore('.'))
}
