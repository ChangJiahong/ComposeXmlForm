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
import cn.changjiahong.composexmlui.node.AttrValue
import cn.changjiahong.composexmlui.node.BoxStyle
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.node.Styled
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.parser.NodeContext
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import cn.changjiahong.composexmlui.renderer.asState
import cn.changjiahong.composexmlui.renderer.containerModifier
import cn.changjiahong.composexmlui.renderer.current

/**
 * 自定义组件示例：新增组件不需要改动引擎核心。
 *
 * 三步：
 * 1. 定义 Node：属性在初始化处直接从 [NodeContext] 读，类型由 `AttrValue<T>` 决定，
 *    **不需要传转换器**；需要尺寸 / padding 就 `Styled by BoxStyle(context)`。
 * 2. 写 Renderer：只读属性用 `current()`，双向绑定用 `asState()`（读写都不用传 owner）。
 * 3. 注册一行：`registry.register(tag, 节点类, 构造引用, 渲染器)`。
 *
 * 继承内置节点同样可行，例如给 `<Text>` 加字间距：
 * ```
 * class RichTextNode(context: NodeContext) : TextNode(context) {
 *     val letterSpacing: AttrValue<TextUnit>? = context.attr("letterSpacing")
 * }
 * registry.register("Text", RichTextNode::class, ::RichTextNode, RichTextRenderer())
 * ```
 * 若只注册节点、不注册渲染器，会沿用 `TextNode` 的渲染器，
 * 新属性**不会报错但也不生效** —— 这是唯一需要额外注意的地方。
 */
class RatingBarNode(context: NodeContext) : Node(context), Styled by BoxStyle(context) {

    /** 评分必须写回数据，所以要求绑定。 */
    val value: AttrValue<Int> = context.needWritable("value")
    val max: AttrValue<Int>? = context.attr("max")
    val label: AttrValue<String>? = context.attr("label")
    val color: AttrValue<Color>? = context.attr("color")

    init {
        context.ensureNoChildren()
    }
}

class RatingBarRenderer : NodeRenderer<RatingBarNode> {

    @Composable
    override fun Render(node: RatingBarNode) {
        val value = node.value.asState()
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
