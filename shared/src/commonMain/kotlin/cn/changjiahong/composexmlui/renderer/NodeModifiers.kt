package cn.changjiahong.composexmlui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.changjiahong.composexmlui.node.Dimension
import cn.changjiahong.composexmlui.node.Styled

/**
 * 通用修饰符：尺寸、内边距、背景色。
 *
 * 只对实现了 [Styled] 的节点可用 —— 没实现 `Styled` 的节点（`<If>` / `<Foreach>`）
 * 在 XML 里写这些属性会在解析期被拒，而不是渲染时静默忽略。
 *
 * 属性值在解析期已经定型（`fill` → [Dimension.Fill]，`12` → `12.dp`），
 * 这里只做「把语义翻译成 Modifier」，不再解析字符串。
 */
@Composable
fun Styled.containerModifier(): Modifier {
    var modifier: Modifier = Modifier

    width?.current()?.let { dimension ->
        modifier = when (dimension) {
            Dimension.Fill -> modifier.fillMaxWidth()
            Dimension.Wrap -> modifier
            is Dimension.Fixed -> modifier.width(dimension.dp)
        }
    }

    height?.current()?.let { dimension ->
        modifier = when (dimension) {
            Dimension.Fill -> modifier.fillMaxHeight()
            Dimension.Wrap -> modifier
            is Dimension.Fixed -> modifier.height(dimension.dp)
        }
    }

    val all = padding?.current()
    val horizontal = paddingHorizontal?.current() ?: all
    val vertical = paddingVertical?.current() ?: all
    if (horizontal != null || vertical != null) {
        modifier = modifier.padding(horizontal = horizontal ?: 0.dp, vertical = vertical ?: 0.dp)
    }

    backgroundColor?.current()?.let { color -> modifier = modifier.background(color) }

    return modifier
}
