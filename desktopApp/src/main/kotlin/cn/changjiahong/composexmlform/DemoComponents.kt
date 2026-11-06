package cn.changjiahong.composexmlform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlui.node.Attrs
import cn.changjiahong.composexmlui.node.BoxStyle
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.node.Styled
import cn.changjiahong.composexmlui.node.ValAttr
import cn.changjiahong.composexmlui.node.VarAttr
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import cn.changjiahong.composexmlui.renderer.asStateOr
import cn.changjiahong.composexmlui.renderer.containerModifier
import cn.changjiahong.composexmlui.renderer.current

/**
 * 自定义组件示例：新增组件不需要改动引擎核心。
 *
 * 三步：
 * 1. 定义 Node：主构造接收显式属性值（`ValAttr<T>` 决定类型，每个属性都有默认值），
 *    另写一个 `(Attrs)` 次构造把 XML 属性搬进来；需要尺寸 / padding 就实现 [Styled]。
 * 2. 写 Renderer：继承 [NodeRenderer]，只读属性用 `current()`，双向绑定用 `asState()` /
 *    `asStateOr()`（后者在没写绑定属性时退化为局部 state）。
 * 3. 注册一行：`registry.register(tag, 节点类, 构造引用, 渲染器)`；容器用 `registerLayout`。
 *
 * 继承内置节点同样可行，例如给 `<Text>` 加字间距：
 * ```
 * class RichTextNode(attrs: Attrs) : TextNode(attrs) {
 *     val letterSpacing: ValAttr<TextUnit>? = attrs.valOf("letterSpacing")
 * }
 * registry.register("Text", RichTextNode::class, ::RichTextNode, RichTextRenderer())
 * ```
 * 若只注册节点、不注册渲染器，会沿用 `TextNode` 的渲染器，
 * 新属性**不会报错但也不生效** —— 这是唯一需要额外注意的地方。
 */
class RatingBarNode(
    /** 评分要写回数据，所以是 [VarAttr]：字面量在解析期就被拒；不写则退化为局部 state。 */
    val value: VarAttr<Int>? = null,
    val max: ValAttr<Int>? = null,
    val label: ValAttr<String>? = null,
    val color: ValAttr<Color>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(attrs: Attrs) : this(
        value = attrs.varOf("value"),
        max = attrs.valOf("max"),
        label = attrs.valOf("label"),
        color = attrs.valOf("color"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}

class RatingBarRenderer : NodeRenderer<RatingBarNode>() {

    @Composable
    override fun Render(node: RatingBarNode) {
        val value = node.value.asStateOr(0)
        val max = node.max?.current() ?: 5
        val current = value.value ?: 0
        val activeColor = node.color?.current() ?: Color(0xFFF5A623)
        val label = node.label?.current()

        Column(modifier = node.containerModifier()) {
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
                            .clickable { value.value = index + 1 }
                            .padding(1.dp),
                    )
                }
            }
        }
    }
}

object DemoComponents {

    fun register(registry: EngineRegistry) {
        registry.register("RatingBar", RatingBarNode::class, ::RatingBarNode, RatingBarRenderer())
    }
}
