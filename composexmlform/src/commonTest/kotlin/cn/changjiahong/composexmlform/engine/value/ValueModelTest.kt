package cn.changjiahong.composexmlform.engine.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 值模型：规范形态、相等、快照。
 *
 * 这一组是「引擎内部只有一种标量表示、一种容器表示」的可执行断言 ——
 * 任何一条挂了，说明有人绕过了 [normalize]。
 */
class ValueModelTest {

    // ── 规范化 ────────────────────────────────────────────────

    @Test
    fun intBecomesLong() {
        val obj = dataObjectOf("a" to 1, "b" to 2)
        assertEquals(1L, obj.member("a"))
        assertEquals(2L, obj.member("b"))
    }

    @Test
    fun floatBecomesDouble() {
        val obj = dataObjectOf("a" to 1.5f, "b" to 2.0)
        assertEquals(1.5, obj.member("a"))
        assertEquals(2.0, obj.member("b"))
    }

    @Test
    fun shortByteCharBecomeLongString() {
        val obj = dataObjectOf("s" to 3.toShort(), "b" to 4.toByte(), "c" to 'x')
        assertEquals(3L, obj.member("s"))
        assertEquals(4L, obj.member("b"))
        assertEquals("x", obj.member("c"))
    }

    @Test
    fun nestedMapBecomesDataObjectAndIsDrillable() {
        val obj = dataObjectOf("user" to mapOf("name" to "张三", "age" to 30))
        val user = assertIs<DataObject>(obj.member("user"))
        assertEquals("张三", user.member("name"))
        assertEquals(30L, user.member("age"))
    }

    @Test
    fun nestedListBecomesDataArray() {
        val obj = dataObjectOf("list" to listOf(1, 2, 3))
        val list = assertIs<DataArray>(obj.member("list"))
        assertEquals(3, list.size)
        assertEquals(1L, list.get(0))
    }

    @Test
    fun unknownObjectBecomesHostObject() {
        val obj = dataObjectOf("x" to StringBuilder("hi"))
        val host = assertIs<HostObject>(obj.member("x"))
        assertEquals("hi", host.raw.toString())
    }

    @Test
    fun nullStaysNull() {
        val obj = dataObjectOf("a" to null)
        assertEquals(null, obj.member("a"))
    }

    // ── Absent 与 null 的区别（V-3）────────────────────────────

    @Test
    fun missingFieldIsAbsentNotNull() {
        val obj = dataObjectOf("a" to null)
        assertEquals(Absent, obj.member("missing"))
        assertEquals(null, obj.member("a"))
    }

    @Test
    fun arrayOutOfBoundsIsAbsent() {
        val array = dataArrayOf(1)
        assertEquals(Absent, array.get(3))
    }

    // ── 相等 ──────────────────────────────────────────────────

    @Test
    fun structuralEqualityIgnoresIdentity() {
        val a = dataObjectOf("x" to 1, "y" to listOf(1, 2))
        val b = dataObjectOf("x" to 1, "y" to listOf(1, 2))
        assertTrue(valuesEqual(a, b))
        assertFalse(sameReference(a, b))
    }

    @Test
    fun structuralEqualityIsOrderInsensitiveForKeys() {
        val a = dataObjectOf("x" to 1, "y" to 2)
        val b = dataObjectOf("y" to 2, "x" to 1)
        assertTrue(valuesEqual(a, b))
    }

    @Test
    fun numbersCompareByValue() {
        assertTrue(valuesEqual(2L, 2.0))
        assertTrue(valuesEqual(2, 2L))
        assertFalse(valuesEqual(2L, 2.5))
    }

    @Test
    fun absentEqualsOnlyAbsent() {
        assertTrue(valuesEqual(Absent, Absent))
        assertFalse(valuesEqual(Absent, null))
        assertFalse(valuesEqual(null, Absent))
    }

    @Test
    fun functionsCompareByIdentity() {
        val f: Function = Fn.noArg { 1L }
        val g: Function = Fn.noArg { 1L }
        assertTrue(valuesEqual(f, f))
        assertFalse(valuesEqual(f, g))
    }

    @Test
    fun cyclicObjectsDoNotHang() {
        val a = dataObjectOf()
        val b = dataObjectOf()
        a.setMember("peer", b)
        b.setMember("peer", a)
        assertTrue(valuesEqual(a, b))
    }

    // ── 快照 ──────────────────────────────────────────────────

    @Test
    fun snapshotSkipsFunctionMembers() {
        val obj = dataObjectOf("name" to "张三")
        obj.setMember("onClick", Fn.noArg { 1L })
        val snapshot = obj.snapshot()
        assertTrue(snapshot.containsKey("name"))
        assertFalse(snapshot.containsKey("onClick"))
    }

    @Test
    fun snapshotIsPlainMap() {
        val obj = dataObjectOf("user" to mapOf("name" to "张三"))
        val snapshot = obj.snapshot()
        val user = assertIs<Map<*, *>>(snapshot["user"])
        assertEquals("张三", user["name"])
    }

    @Test
    fun snapshotReplacesFunctionsInArraysWithAbsent() {
        val array = dataArrayOf("a", 1)
        array.append(Fn.noArg { 1L })
        val obj = dataObjectOf()
        obj.setMember("list", array)
        val list = assertIs<List<*>>(obj.snapshot()["list"])
        assertEquals(3, list.size)
        assertSame(Absent, list[2])
    }

    // ── 引用语义 ──────────────────────────────────────────────

    @Test
    fun writingKeepsSameInstance() {
        val child = dataObjectOf("name" to "张三")
        val root = dataObjectOf()
        root.setMember("user", child)
        assertSame(child, root.member("user"))
        child.setMember("name", "李四")
        assertEquals("李四", (root.member("user") as DataObject).member("name"))
    }

    @Test
    fun frozenObjectCopiedWhenAssignedIntoWritable() {
        val configDict = DataObject.frozen(mapOf("status" to "draft"))
        val data = dataObjectOf()
        data.setMember("draft", configDict)

        val draft = assertIs<DataObject>(data.member("draft"))
        assertFalse(draft.frozen, "跨能力赋值应得到可写副本（S-2）")
        draft.setMember("status", "published")

        assertEquals("draft", (configDict as DataObject).member("status"), "原只读对象不受影响")
        assertEquals("published", draft.member("status"))
    }

    @Test
    fun writingFrozenObjectThrows() {
        val config = DataObject.frozen(mapOf("k" to 1))
        kotlin.test.assertFailsWith<cn.changjiahong.composexmlform.engine.diag.FrozenWriteException> {
            config.setMember("k", 2L)
        }
    }
}
