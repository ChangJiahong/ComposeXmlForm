package cn.changjiahong.composexmlform.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.changjiahong.composexmlform.node.BoxStyle
import cn.changjiahong.composexmlform.node.Dimension

/**
 * 通用修饰符：尺寸、内边距、背景色。
 *
 * 接收者是 [BoxStyle] 而不是节点：`Row` / `Column` / `Box` 与 `Text` / `Button` 分属
 * 两条继承链（一个有 children、一个没有），共同点只有「持有一个 BoxStyle」。
 * 挂在 BoxStyle 上，两条链都只需 `node.style.containerModifier()`，不需要为它再造一个接口。
 *
 * 属性值在解析期已经定型（`fill` → [Dimension.Fill]，`12` → `12f`），
 * 这里只做「把语义翻译成 Modifier」，不再解析字符串；单位（`dp`）在这一层加。
 */
@Composable
fun BoxStyle.containerModifier(): Modifier {
    var modifier: Modifier = Modifier

    width?.current()?.let { dimension ->
        modifier = when (dimension) {
            Dimension.Fill -> modifier.fillMaxWidth()
            Dimension.Wrap -> modifier
            is Dimension.Fixed -> modifier.width(dimension.value.dp)
        }
    }

    height?.current()?.let { dimension ->
        modifier = when (dimension) {
            Dimension.Fill -> modifier.fillMaxHeight()
            Dimension.Wrap -> modifier
            is Dimension.Fixed -> modifier.height(dimension.value.dp)
        }
    }

    val all = padding?.current()
    val horizontal = paddingHorizontal?.current() ?: all
    val vertical = paddingVertical?.current() ?: all
    if (horizontal != null || vertical != null) {
        modifier = modifier.padding(horizontal = (horizontal ?: 0f).dp, vertical = (vertical ?: 0f).dp)
    }

    // 非法颜色回退 Color.Unspecified，等于「没设背景」，而不是画一层看不见的东西。
    backgroundColor?.current()?.toColor()
        ?.takeIf { it != Color.Unspecified }
        ?.let { color -> modifier = modifier.background(color) }

    return modifier
}
