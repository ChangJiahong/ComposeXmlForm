package cn.changjiahong.composexmlui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import cn.changjiahong.composexmlui.node.BoxNode
import cn.changjiahong.composexmlui.node.ColumnNode
import cn.changjiahong.composexmlui.node.RowNode
import cn.changjiahong.composexmlui.renderer.Attrs
import cn.changjiahong.composexmlui.renderer.NodeRenderer
import cn.changjiahong.composexmlui.renderer.RenderNode
import cn.changjiahong.composexmlui.renderer.containerModifier
import cn.changjiahong.composexmlui.renderer.enumAttr

/**
 * 布局节点渲染器：只负责创建 Compose 容器并递归渲染子节点，
 * 不解析 XML、不管理业务数据。
 */
class ColumnNodeRenderer : NodeRenderer<ColumnNode> {

    @Composable
    override fun Render(node: ColumnNode) {
        val scroll = Attrs.bool(node, "scroll") ?: false
        val spacing = Attrs.dp(node, "spacing")
        val arrangement = if (spacing != null) {
            Arrangement.spacedBy(spacing)
        } else {
            node.enumAttr(
                "arrangement",
                mapOf(
                    "top" to Arrangement.Top,
                    "center" to Arrangement.Center,
                    "bottom" to Arrangement.Bottom,
                    "spaceBetween" to Arrangement.SpaceBetween,
                    "spaceAround" to Arrangement.SpaceAround,
                    "spaceEvenly" to Arrangement.SpaceEvenly,
                ),
                Arrangement.Top,
            )
        }
        val alignment = node.enumAttr(
            "horizontalAlignment",
            mapOf(
                "start" to Alignment.Start,
                "center" to Alignment.CenterHorizontally,
                "end" to Alignment.End,
            ),
            Alignment.Start,
        )

        var modifier = node.containerModifier()
        if (scroll) modifier = modifier.verticalScroll(rememberScrollState())

        Column(
            modifier = modifier,
            verticalArrangement = arrangement,
            horizontalAlignment = alignment,
        ) {
            node.children.forEach { RenderNode(it) }
        }
    }
}

class RowNodeRenderer : NodeRenderer<RowNode> {

    @Composable
    override fun Render(node: RowNode) {
        val scroll = Attrs.bool(node, "scroll") ?: false
        val spacing = Attrs.dp(node, "spacing")
        val arrangement = if (spacing != null) {
            Arrangement.spacedBy(spacing)
        } else {
            node.enumAttr(
                "arrangement",
                mapOf(
                    "start" to Arrangement.Start,
                    "center" to Arrangement.Center,
                    "end" to Arrangement.End,
                    "spaceBetween" to Arrangement.SpaceBetween,
                    "spaceAround" to Arrangement.SpaceAround,
                    "spaceEvenly" to Arrangement.SpaceEvenly,
                ),
                Arrangement.Start,
            )
        }
        val alignment = node.enumAttr(
            "verticalAlignment",
            mapOf(
                "top" to Alignment.Top,
                "center" to Alignment.CenterVertically,
                "bottom" to Alignment.Bottom,
            ),
            Alignment.Top,
        )

        var modifier = node.containerModifier()
        if (scroll) modifier = modifier.horizontalScroll(rememberScrollState())

        Row(
            modifier = modifier,
            horizontalArrangement = arrangement,
            verticalAlignment = alignment,
        ) {
            node.children.forEach { RenderNode(it) }
        }
    }
}

class BoxNodeRenderer : NodeRenderer<BoxNode> {

    @Composable
    override fun Render(node: BoxNode) {
        val alignment = node.enumAttr(
            "contentAlignment",
            mapOf(
                "center" to Alignment.Center,
                "topStart" to Alignment.TopStart,
                "topCenter" to Alignment.TopCenter,
                "topEnd" to Alignment.TopEnd,
                "centerStart" to Alignment.CenterStart,
                "centerEnd" to Alignment.CenterEnd,
                "bottomStart" to Alignment.BottomStart,
                "bottomCenter" to Alignment.BottomCenter,
                "bottomEnd" to Alignment.BottomEnd,
            ),
            Alignment.TopStart,
        )

        Box(modifier = node.containerModifier(), contentAlignment = alignment) {
            node.children.forEach { RenderNode(it) }
        }
    }
}
