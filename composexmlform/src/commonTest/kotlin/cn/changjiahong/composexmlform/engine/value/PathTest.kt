package cn.changjiahong.composexmlform.engine.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathTest {

    @Test
    fun parsesDottedPathIntoRelativeSegments() {
        val path = Path.parse("data.user.name")
        assertEquals(listOf("data", "user", "name"), path?.segments)
        assertNull(path?.root, "是否绝对由解析器查 schema 决定，Path.parse 不管")
        assertFalse(path!!.isAbsolute)
    }

    @Test
    fun parsesIndexSegments() {
        assertEquals(listOf("list", "0", "name"), Path.parse("list[0].name")?.segments)
        assertEquals(listOf("a", "1", "2"), Path.parse("a[1][2]")?.segments)
    }

    @Test
    fun rejectsMalformedPaths() {
        assertNull(Path.parse(""))
        assertNull(Path.parse("  "))
        assertNull(Path.parse("a..b"))
        assertNull(Path.parse(".a"))
        assertNull(Path.parse("a."))
        assertNull(Path.parse("a[0"))
        assertNull(Path.parse("a[]"))
        assertNull(Path.parse("a-b"), "路径段只允许标识符与下标")
        assertNull(Path.parse("a + b"))
    }

    @Test
    fun headAndRest() {
        val path = Path.absolute("data", "user", "name")
        assertEquals("data", path.head)
        assertEquals(listOf("user", "name"), path.rest)
    }

    @Test
    fun childAppendsSegmentKeepingRoot() {
        val path = Path.absolute("data", "user").child("name")
        assertEquals(Path.absolute("data", "user", "name"), path)
    }

    @Test
    fun fallbackOnlyAppliesToRelative() {
        assertEquals("data", Path.relative("user").fallbackTo("data").root)
        assertEquals("config", Path.absolute("config", "x").fallbackTo("data").root)
    }

    @Test
    fun replacePrefixUsesOriginAsBase() {
        // 引用写入：u = data.user（origin = data.user）+ 本次的 .name
        val origin = Path.absolute("data", "user")
        val written = Path.relative("u", "name").replacePrefix(1, origin)
        assertEquals(Path.absolute("data", "user", "name"), written)
    }

    @Test
    fun displayIsHumanReadable() {
        assertEquals("data.user.name", Path.absolute("data", "user", "name").display())
        assertEquals("user.name", Path.relative("user", "name").display())
    }

    @Test
    fun absoluteIsFastPathMarker() {
        assertTrue(Path.absolute("data", "x").isAbsolute)
        assertFalse(Path.relative("x").isAbsolute)
    }
}
