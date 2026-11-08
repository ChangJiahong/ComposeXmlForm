package cn.changjiahong.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // 三端统一测试台：宽度 ≥ 840dp 三栏并置，窄屏走单页标签
            Playground()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}