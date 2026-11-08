package cn.changjiahong.sample

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ComposeXmlUI 调试台",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
        // 与 Android / iOS 共用同一份测试台：宽窗口下自动变成三栏布局
        Playground()
    }
}
