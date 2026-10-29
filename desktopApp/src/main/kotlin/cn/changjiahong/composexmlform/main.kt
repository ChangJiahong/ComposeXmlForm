package cn.changjiahong.composexmlform

import androidx.compose.material3.MaterialTheme
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
        MaterialTheme {
            DevApp()
        }
    }
}
