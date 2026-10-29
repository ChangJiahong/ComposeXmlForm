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
import cn.changjiahong.composexmlui.node.Node
import cn.changjiahong.composexmlui.parser.EngineRegistry
import cn.changjiahong.composexmlui.renderer.Attrs
import cn.changjiahong.composexmlui.renderer.LocalStoreOwner
import cn.changjiahong.composexmlui.renderer.containerModifier

/**
 * 自定义组件示例：证明「新增组件不需要修改引擎核心」。
 *
 * 只需三步：
 * 1. 定义 Node（只存属性）；
 * 2. 写渲染器（读 Store、写 Store 都走统一机制）；
 * 3. 向 EngineRegistry 注册「标签 + 属性白名单 + 工厂 + 渲染器」。
 */
class RatingBarNode(attributes: Map<String, String>, line: Int = 0) : Node(attributes, line) {
    override val tag: String get() = "RatingBar"
}

object DemoComponents {

    fun register(registry: EngineRegistry) {
        registry.register(
            tag = "RatingBar",
            nodeClass = RatingBarNode::class,
            allowedAttributes = setOf("value", "max", "label", "color"),
            factory = { context ->
                context.ensureNoChildren()
                context.requireBinding("value")
                RatingBarNode(context.attributes, context.line)
            },
            render = { node -> RatingBarView(node) },
        )
    }

    @Composable
    private fun RatingBarView(node: RatingBarNode) {
        val owner = LocalStoreOwner.current
        val max = node.raw("max")?.trim()?.toIntOrNull() ?: 5
        val expression = node.expression("value")
        val current = owner.readInt(expression) ?: 0
        val activeColor = Attrs.color(node, "color") ?: Color(0xFFF5A623)
        val label = node.raw("label")

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
                            .clickable { owner.write(expression, index + 1) }
                            .padding(1.dp),
                    )
                }
            }
        }
    }
}
