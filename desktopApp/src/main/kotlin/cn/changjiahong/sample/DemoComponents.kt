package cn.changjiahong.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.StyledNode
import cn.changjiahong.composexmlform.node.ValAttr
import cn.changjiahong.composexmlform.node.VarAttr
import cn.changjiahong.composexmlform.api.ComponentModule
import cn.changjiahong.composexmlform.renderer.NodeRenderer
import cn.changjiahong.composexmlform.renderer.containerModifier
import cn.changjiahong.composexmlform.renderer.current
import cn.changjiahong.composexmlform.renderer.get
import cn.changjiahong.composexmlform.renderer.provideDelegate
import cn.changjiahong.composexmlform.renderer.toColor

/**
 * 自定义组件示例：新增组件不需要改动引擎核心。
 *
 * 三步：
 * 1. 定义 Node：主构造收 `Attrs`，属性**声明在类体里**（`attrs.valOf("max")`），
 *    读过的属性才算「支持」，没读的会报 `UnknownAttribute`；需要尺寸 / padding 就继承 [StyledNode]。
 *    `Attrs.Empty` 作为默认值，节点因此也能被程序化构造（单测 / 预览）。
 * 2. 写 Renderer：实现 [NodeRenderer]，只读属性用 `current()`，双向绑定用 `var x by` /
 *    `asStateOr()`（后者在没写绑定属性时退化为局部 state）。
 * 3. 注册一行：`registry.leaf(tag, ::节点构造, 渲染器)`；容器用 `container`。
 *
 * 继承内置节点同样可行，例如给 `<Text>` 加字间距（`replace` 要求标签已存在，正好用在这里）：
 * ```
 * class RichTextNode(attrs: Attrs) : TextNode(attrs) {
 *     val letterSpacing: ValAttr<Float>? = attrs.valOf("letterSpacing")
 * }
 * registry.replace("Text", ::RichTextNode, RichTextRenderer())
 * ```
 * 若把原渲染器传进去（`replace("Text", ::RichTextNode, TextNodeRenderer())`），
 * 新属性**不会报错但也不生效** —— 属性被节点读走了，渲染器没用它。这是唯一需要额外注意的地方。
 */
class RatingBarNode(attrs: Attrs = Attrs.Empty) : StyledNode(attrs) {

    /** 评分要写回数据，所以是 [VarAttr]：字面量在解析期就被拒；不写则退化为局部 state。 */
    val value: VarAttr<Int>? = attrs.varOf("value")
    val max: ValAttr<Int>? = attrs.valOf("max")
    val label: ValAttr<String>? = attrs.valOf("label")
    val color: ValAttr<String>? = attrs.valOf("color")
}

class RatingBarRenderer : NodeRenderer<RatingBarNode> {

    @Composable
    override fun Render(node: RatingBarNode) {
        var value by node.value
        val max = node.max.get(5)
        val current = value ?: 0
        val activeColor = node.color?.current().toColor()
            .takeIf { it != Color.Unspecified } ?: Color(0xFFF5A623)
        val label = node.label?.current()

        Column(modifier = node.style.containerModifier()) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(max) { index ->
                    Text(
                        text = "★",
                        fontSize = 26.sp,
                        color = if (index < current) activeColor else Color(0xFFD8D8D8),
                        modifier = Modifier
                            .clickable { value = index + 1 }
                            .padding(1.dp),
                    )
                }
            }
        }
    }
}

/**
 * 组件包：一个 `ComponentModule` 值，不再需要 `object` + `fun register`。
 * 调用侧写 `ComposeXmlForm.createRegistry { install(DemoComponents) }`。
 */
val DemoComponents = ComponentModule { registry ->
    registry.leaf("RatingBar", ::RatingBarNode, RatingBarRenderer())
}
