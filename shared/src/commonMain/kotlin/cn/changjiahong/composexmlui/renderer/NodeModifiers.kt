package cn.changjiahong.composexmlui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.node.Node

/**
 * 通用容器修饰符：尺寸、内边距、背景色。
 *
 * 支持 `width="fill"` / `width="200"` / `height="fill"` 这类写法，
 * 所有渲染器共用，避免重复实现。
 */
fun Node.containerModifier(): Modifier {
    var modifier: Modifier = Modifier

    raw("width")?.let { raw ->
        when (raw.trim().lowercase()) {
            "fill", "fillmax", "match", "match_parent" -> modifier = modifier.fillMaxWidth()
            "wrap", "wrap_content", "auto" -> Unit
            else -> modifier = modifier.width(Attrs.dp(this, "width")!!)
        }
    }

    raw("height")?.let { raw ->
        when (raw.trim().lowercase()) {
            "fill", "fillmax", "match", "match_parent" -> modifier = modifier.fillMaxHeight()
            "wrap", "wrap_content", "auto" -> Unit
            else -> modifier = modifier.height(Attrs.dp(this, "height")!!)
        }
    }

    val padding = Attrs.dp(this, "padding")
    val horizontal = Attrs.dp(this, "paddingHorizontal") ?: padding
    val vertical = Attrs.dp(this, "paddingVertical") ?: padding
    if (horizontal != null || vertical != null) {
        modifier = modifier.padding(horizontal = horizontal ?: 0.dp, vertical = vertical ?: 0.dp)
    }

    Attrs.color(this, "backgroundColor")?.let { color -> modifier = modifier.background(color) }

    return modifier
}

/** 供布局渲染器解析对齐/排列等枚举属性。 */
internal fun <T> Node.enumAttr(name: String, mapping: Map<String, T>, default: T): T {
    val raw = raw(name)?.trim() ?: return default
    return mapping[raw.lowercase()] ?: throw XmlUiException(
        "属性值 '$raw' 无效，期望 ${mapping.keys.sorted().joinToString(" / ")}",
        tag = tag,
        attribute = name,
        line = line,
    )
}
