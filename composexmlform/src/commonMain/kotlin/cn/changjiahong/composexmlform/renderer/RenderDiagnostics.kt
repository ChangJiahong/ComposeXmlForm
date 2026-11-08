package cn.changjiahong.composexmlform.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.node.Node

/**
 * 渲染期诊断上报通道：动作失败、非法属性值、节点渲染失败都往这里送。
 *
 * 默认是空实现（没有宿主时静默丢弃）。宿主想要日志 / 埋点就自己在外层
 * `CompositionLocalProvider(LocalRenderDiagnostics provides { ... })`，
 * **不需要给 `DynamicLayout` 加参数**。
 */
val LocalRenderDiagnostics = staticCompositionLocalOf<(Diagnostic) -> Unit> { { } }

/**
 * 节点渲染失败时的占位。默认是「灰底虚线框 + 类型名」，与未知标签占位同一套视觉语言。
 *
 * 宿主可以替换成「重试按钮 / 上报入口 / 折叠详情」：
 * ```
 * CompositionLocalProvider(
 *     LocalRenderFallback provides { node, diagnostic -> MyBrokenNodeCard(node, diagnostic) },
 * ) { DynamicLayout(...) }
 * ```
 *
 * 参数是 `(节点, 诊断)`，诊断在「渲染器未注册」与「渲染器抛错」两种情况下都不为 null ——
 * 占位块想让用户看到什么信息，由宿主决定。
 */
val LocalRenderFallback =
    staticCompositionLocalOf<(@Composable (Node, Diagnostic?) -> Unit)?> { null }

/** 默认占位：一眼能看出「这里坏了」，但不抢走整页。 */
@Composable
internal fun DefaultRenderFallback(node: Node, diagnostic: Diagnostic?) {
    val label = node::class.simpleName ?: "Node"
    Text(
        text = "<$label> 渲染失败",
        color = Color(0xFF8A2B2B),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier
            .background(Color(0xFFFFEDED))
            .border(1.dp, Color(0xFFE4A0A0))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
