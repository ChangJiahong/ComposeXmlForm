package cn.changjiahong.composexmlform.engine.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 收敛规则。这是**全系统唯一的类型转换点**，所以每条规则都要能被单独引用。
 */
class TypeAdapterTest {

    private val site = AttrSite(tag = "Text", attribute = "value")

    // ── String ────────────────────────────────────────────────

    @Test
    fun stringLiteralIsRaw() {
        assertEquals("hello", StringAdapter.literal("hello", site))
    }

    @Test
    fun stringCoercesNumbersAndBooleans() {
        assertEquals("3", StringAdapter.coerce(3L, site))
        assertEquals("3", StringAdapter.coerce(3.0, site), "整数值不显示 .0")
        assertEquals("1.5", StringAdapter.coerce(1.5, site))
        assertEquals("true", StringAdapter.coerce(true, site))
    }

    @Test
    fun stringRejectsContainer() {
        assertFailsWith<CoerceException> { StringAdapter.coerce(dataObjectOf(), site) }
    }

    @Test
    fun absentAndNullAreDistinguishedInMessage() {
        val absent = assertFailsWith<CoerceException> { StringAdapter.coerce(Absent, site) }
        val nul = assertFailsWith<CoerceException> { StringAdapter.coerce(null, site) }
        assertTrue(absent.message!!.contains("不存在"))
        assertTrue(nul.message!!.contains("null"))
    }

    @Test
    fun stringDefaultIsEmpty() {
        assertEquals("", StringAdapter.default())
    }

    // ── 数字 ──────────────────────────────────────────────────

    @Test
    fun longLiteralParsesAndRejects() {
        assertEquals(42L, LongAdapter.literal(" 42 ", site))
        val error = assertFailsWith<CoerceException> { LongAdapter.literal("4.2", site) }
        assertTrue(error.message!!.contains("不是整数"))
    }

    @Test
    fun longAcceptsIntegralDoubleOnly() {
        assertEquals(3L, LongAdapter.coerce(3.0, site))
        assertFailsWith<CoerceException> { LongAdapter.coerce(3.5, site) }
    }

    @Test
    fun longAcceptsNumericString() {
        assertEquals(7L, LongAdapter.coerce("7", site))
        assertFailsWith<CoerceException> { LongAdapter.coerce("七", site) }
    }

    @Test
    fun doubleAcceptsLongAndString() {
        assertEquals(3.0, DoubleAdapter.coerce(3L, site))
        assertEquals(2.5, DoubleAdapter.coerce("2.5", site))
    }

    @Test
    fun intRejectsOverflow() {
        assertEquals(3, IntAdapter.coerce(3L, site))
        assertFailsWith<CoerceException> { IntAdapter.coerce(Long.MAX_VALUE, site) }
    }

    @Test
    fun floatGoesThroughDouble() {
        assertEquals(1.5f, FloatAdapter.coerce("1.5", site))
    }

    // ── 布尔（P-3 严格）──────────────────────────────────────

    @Test
    fun booleanLiteralOnlyTrueOrFalse() {
        assertEquals(true, BooleanAdapter.literal("true", site))
        assertEquals(false, BooleanAdapter.literal("false", site))
        assertFailsWith<CoerceException> { BooleanAdapter.literal("yes", site) }
    }

    @Test
    fun booleanRejectsTruthyValues() {
        assertFailsWith<CoerceException> { BooleanAdapter.coerce(1L, site) }
        assertFailsWith<CoerceException> { BooleanAdapter.coerce("", site) }
    }

    @Test
    fun booleanDefaultIsFalse() {
        assertEquals(false, BooleanAdapter.default())
    }

    // ── 函数 ──────────────────────────────────────────────────

    @Test
    fun functionSlotRejectsLiteral() {
        assertFailsWith<CoerceException> { FunctionAdapter.literal("login", site) }
        assertEquals(SlotKind.Function, FunctionAdapter.slotKind)
    }

    @Test
    fun functionSlotAcceptsFunctionValue() {
        val fn = Fn.noArg { 1L }
        assertEquals(fn, FunctionAdapter.coerce(fn, site))
    }

    @Test
    fun dataSlotsUseDataKind() {
        assertEquals(SlotKind.Data, StringAdapter.slotKind)
        assertEquals(SlotKind.Data, BooleanAdapter.slotKind)
    }

    // ── 无默认值 ──────────────────────────────────────────────

    @Test
    fun numbersHaveNoDefaultValue() {
        assertNull(LongAdapter.default())
        assertNull(DoubleAdapter.default())
        assertNull(FunctionAdapter.default(), "函数没有「空函数」这种默认值")
    }

    // ── 数字显示规则 ──────────────────────────────────────────

    @Test
    fun formatDoubleDropsTrailingZero() {
        assertEquals("3", formatDouble(3.0))
        assertEquals("1.5", formatDouble(1.5))
        assertEquals("-2", formatDouble(-2.0))
    }
}
