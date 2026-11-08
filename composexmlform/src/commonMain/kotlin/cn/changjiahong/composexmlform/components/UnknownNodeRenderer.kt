package cn.changjiahong.composexmlform.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlform.node.UnknownNode
import cn.changjiahong.composexmlform.renderer.NodeRenderer

/**
 * 未知标签的占位渲染：灰底虚线框 + 标签名。
 *
 * 目的是**让问题可见**而不阻断界面：用户看到一块明显的占位，
 * 开发者在 `diagnostics` 里看到 `UnknownTag`（含行号）。
 * 有意不加点击 / 展开交互 —— R10 的可定制 fallback 才是放这些东西的地方。
 */
class UnknownNodeRenderer : NodeRenderer<UnknownNode> {

    @Composable
    override fun Render(node: UnknownNode) {
        Text(
            text = "<${node.tagName}>",
            color = Color(0xFF8A6D3B),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .background(Color(0xFFFFF8E1))
                .border(1.dp, Color(0xFFE0C97F))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
