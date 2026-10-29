package cn.changjiahong.composexmlui.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node

/**
 * 统一的属性解析：避免每个 Renderer 各写一遍字符串 → Compose 类型的转换，
 * 并保证错误信息一致（标签 / 属性 / 行号 / 期望格式）。
 */
object Attrs {

    fun dp(node: Node, name: String): Dp? = node.raw(name)?.let { parseDp(node, name, it) }

    /** 字号：`14` / `14sp` → 14.sp */
    fun fontSize(node: Node, name: String): TextUnit? {
        val raw = node.raw(name)?.trim() ?: return null
        val number = raw.removeSuffix("sp").removeSuffix("SP").trim().toFloatOrNull()
            ?: fail(node, name, raw, "数字，单位 sp（例如 14 或 14sp）")
        return number.sp
    }

    fun int(node: Node, name: String): Int? {
        val raw = node.raw(name)?.trim() ?: return null
        return raw.toIntOrNull() ?: fail(node, name, raw, "整数")
    }

    fun float(node: Node, name: String): Float? {
        val raw = node.raw(name)?.trim() ?: return null
        return raw.toFloatOrNull() ?: fail(node, name, raw, "数字")
    }

    fun bool(node: Node, name: String): Boolean? {
        val raw = node.raw(name)?.trim() ?: return null
        return when (raw.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> fail(node, name, raw, "true 或 false")
        }
    }

    fun color(node: Node, name: String): Color? {
        val raw = node.raw(name)?.trim() ?: return null
        if (raw.startsWith("#")) {
            val hex = raw.substring(1)
            val argb = when (hex.length) {
                3 -> {
                    val r = hex[0]
                    val g = hex[1]
                    val b = hex[2]
                    "FF$r$r$g$g$b$b"
                }
                6 -> "FF$hex"
                8 -> hex
                else -> fail(node, name, raw, "#RGB、#RRGGBB 或 #AARRGGBB")
            }
            val value = argb.lowercase().toLongOrNull(16)
                ?: fail(node, name, raw, "合法的十六进制颜色")
            return Color(value)
        }
        return NAMED_COLORS[raw.lowercase()]
            ?: fail(node, name, raw, "十六进制颜色（如 #FF5722）或内置色名 ${NAMED_COLORS.keys.sorted()}")
    }

    fun textAlign(node: Node, name: String): TextAlign? {
        val raw = node.raw(name)?.trim() ?: return null
        return when (raw.lowercase()) {
            "start", "left" -> TextAlign.Start
            "center" -> TextAlign.Center
            "end", "right" -> TextAlign.End
            else -> fail(node, name, raw, "start / center / end")
        }
    }

    fun fontWeight(node: Node, name: String): FontWeight? {
        val raw = node.raw(name)?.trim() ?: return null
        return when (raw.lowercase()) {
            "normal", "regular" -> FontWeight.Normal
            "medium" -> FontWeight.Medium
            "semibold", "semi_bold" -> FontWeight.SemiBold
            "bold" -> FontWeight.Bold
            else -> {
                val number = raw.toIntOrNull() ?: fail(node, name, raw, "normal / medium / semibold / bold 或 100~900")
                if (number in 1..9) FontWeight(number * 100) else FontWeight(number)
            }
        }
    }

    private fun parseDp(node: Node, name: String, raw: String): Dp {
        val text = raw.trim().removeSuffix("dp").removeSuffix("DP").trim()
        val value = text.toFloatOrNull() ?: fail(node, name, raw, "数字，单位 dp（例如 12 或 12dp）")
        return value.dp
    }

    private fun fail(node: Node, name: String, raw: String, expected: String): Nothing =
        throw XmlUiException(
            "属性值 '$raw' 无效，期望 $expected",
            tag = node.tag,
            attribute = name,
            line = node.line,
        )

    private val NAMED_COLORS: Map<String, Color> = mapOf(
        "black" to Color(0xFF000000),
        "white" to Color(0xFFFFFFFF),
        "transparent" to Color(0x00000000),
        "red" to Color(0xFFF44336),
        "green" to Color(0xFF4CAF50),
        "blue" to Color(0xFF2196F3),
        "yellow" to Color(0xFFFFEB3B),
        "orange" to Color(0xFFFF9800),
        "purple" to Color(0xFF9C27B0),
        "gray" to Color(0xFF9E9E9E),
        "grey" to Color(0xFF9E9E9E),
        "lightgray" to Color(0xFFF5F5F5),
        "lightgrey" to Color(0xFFF5F5F5),
    )
}
