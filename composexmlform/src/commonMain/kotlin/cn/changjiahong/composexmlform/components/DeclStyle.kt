package cn.changjiahong.composexmlform.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.changjiahong.composexmlform.api.decl.Prop
import cn.changjiahong.composexmlform.api.decl.Props

/**
 * 六个样式属性的**一次声明**：节点构造函数里写 `val style = props.style()` 就全有了。
 *
 * 与旧的 [BoxStyle] 的区别只有一处：样式槽位是委托出来的值（`Prop`），不是槽位对象，
 * 所以 `modifier()` 不再是 `@Composable` —— 读值不需要组合上下文，`Props` 里已经带着现场。
 */
class StyleFields internal constructor(props: Props) {

    private val width: Prop<Dimension?> = props.field("width", StyleAttrs.width, null)
    private val height: Prop<Dimension?> = props.field("height", StyleAttrs.height, null)
    private val padding: Prop<Float?> = props.field("padding", StyleAttrs.padding, null)
    private val paddingHorizontal: Prop<Float?> =
        props.field("paddingHorizontal", StyleAttrs.paddingHorizontal, null)
    private val paddingVertical: Prop<Float?> =
        props.field("paddingVertical", StyleAttrs.paddingVertical, null)
    private val backgroundColor: Prop<String?> =
        props.field("backgroundColor", StyleAttrs.backgroundColor, null)

    /** 样式 → Modifier。单位（`dp`）只在这一层加。 */
    fun modifier(): Modifier {
        var modifier: Modifier = Modifier

        width.get()?.let { dimension ->
            modifier = when (dimension) {
                Dimension.Fill -> modifier.fillMaxWidth()
                Dimension.Wrap -> modifier
                is Dimension.Fixed -> modifier.width(dimension.value.dp)
            }
        }

        height.get()?.let { dimension ->
            modifier = when (dimension) {
                Dimension.Fill -> modifier.fillMaxHeight()
                Dimension.Wrap -> modifier
                is Dimension.Fixed -> modifier.height(dimension.value.dp)
            }
        }

        val all = padding.get()
        val horizontal = paddingHorizontal.get() ?: all
        val vertical = paddingVertical.get() ?: all
        if (horizontal != null || vertical != null) {
            modifier = modifier.padding(horizontal = (horizontal ?: 0f).dp, vertical = (vertical ?: 0f).dp)
        }

        backgroundColor.get().toColor()
            .takeIf { it != Color.Unspecified }
            ?.let { color -> modifier = modifier.background(color) }

        return modifier
    }
}

/** 节点构造函数里的 `val style = props.style()`。 */
fun Props.style(): StyleFields = StyleFields(this)
