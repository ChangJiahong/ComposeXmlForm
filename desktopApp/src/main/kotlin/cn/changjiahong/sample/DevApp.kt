package cn.changjiahong.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlform.ComposeXmlForm
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.api.DynamicLayout
import cn.changjiahong.composexmlform.api.actions
import cn.changjiahong.composexmlform.api.install
import cn.changjiahong.composexmlform.core.JsonCodec
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime

private val SAMPLE_XML = """
<Column scroll="true" padding="20" spacing="14" width="fill">
    <Text value="用户信息" fontSize="22" fontWeight="bold" />
    <Row spacing="6" verticalAlignment="center">
        <Text value="当前用户：" color="#666666" />
        <Text value="{{username}}" fontWeight="bold" />
    </Row>

    <TextField label="姓名" placeholder="请输入姓名" value="{{username}}" />
    <Select label="城市" options="{{config.regions}}" value="{{region}}" placeholder="请选择城市" />

    <If test="{{isLogin}}">
        <Then>
            <Text value="状态：已登录" color="#1B7F3B" />
            <Button text="退出登录" action="logout()" />
        </Then>
        <Else>
            <Text value="状态：未登录" color="#C0392B" />
            <Button text="登录" action="login()" />
        </Else>
    </If>

    <Text value="联系人（修改输入框会写回 contacts 元素）" fontSize="14" fontWeight="bold" />
    <Foreach items="{{contacts}}" item="item" key="id">
        <Column spacing="8" padding="12" backgroundColor="#F3F4F6">
            <Text value="{{item.name}}" fontWeight="bold" />
            <TextField label="电话" value="{{item.phone}}" />
        </Column>
    </Foreach>

    <RatingBar label="评分（自定义组件）" value="{{rating}}" max="5" />

    <Button text="提交" action="submit()" width="fill" />
</Column>
""".trimIndent()

private val INITIAL_DATA: Map<String, Any?> = mapOf(
    "username" to "张三",
    "region" to "BJ",
    "isLogin" to true,
    "rating" to 3,
    "contacts" to listOf(
        mapOf("id" to 1, "name" to "李雷", "phone" to "13800000001"),
        mapOf("id" to 2, "name" to "韩梅梅", "phone" to "13800000002"),
    ),
)

private val DEMO_CONFIG: Map<String, Any?> = mapOf(
    "regions" to listOf(
        mapOf("label" to "北京", "value" to "BJ"),
        mapOf("label" to "上海", "value" to "SH"),
        mapOf("label" to "广州", "value" to "GZ"),
    ),
)

private var contactSeq = 10

/**
 * 桌面调试台：左边改 XML，右边实时渲染，底部实时显示业务数据快照。
 * 这是引擎开发期的主要验证工具。
 */
@Composable
fun DevApp() {
    val registry = remember { ComposeXmlForm.createRegistry { install(DemoComponents) } }

    var xmlText by remember { mutableStateOf(SAMPLE_XML) }

    // 业务数据由调用方持有：改 XML 只会重建运行时，用户已经填进去的内容不会丢。
    val formData = remember { BindableData.of(INITIAL_DATA) }

    // 订阅整表快照：输入框里的每一次修改都能在这里看到（内部写回也走同一份状态）。
    // 真实业务里如果表单很大，逐字段用 formData.get("...") 读更划算，asState 会重建整份快照。
    val snapshot by formData.asState()

    // 动作用 DSL 声明：名字写错、重复注册都是构建期报错，而不是点击时静默无事发生。
    // handler 是 suspend 的，拿得到 ActionScope.data —— 不需要从闭包里捕获 formData。
    val actionSet = remember {
        actions {
            on("submit") { println("[demo] submit -> ${data.getJson()}") }
            on("login") { data.set("isLogin", true) }
            on("logout") { data.set("isLogin", false) }
        }
    }

    // 渲染前先校验：解析 / 属性 / 结构 / 动作注册，任何错误都以面板形式展示，不炸 composition。
    val validation = remember(xmlText, registry, actionSet) {
        runCatching {
            DynamicLayoutRuntime(xmlText, formData, DEMO_CONFIG, actionSet, registry)
        }
    }
    val error = validation.exceptionOrNull()

    Row(modifier = Modifier.fillMaxSize()) {

        // ---------------- 左：XML 编辑 ----------------
        Column(modifier = Modifier.width(460.dp).fillMaxHeight().padding(12.dp)) {
            Text("XML 源（编辑后实时生效）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = xmlText,
                onValueChange = { xmlText = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { xmlText = SAMPLE_XML }) { Text("重置") }
                Button(onClick = {
                    formData.list("contacts").add(
                        mapOf(
                            "id" to ++contactSeq,
                            "name" to "新联系人$contactSeq",
                            "phone" to "1380000000$contactSeq",
                        ),
                    )
                }) { Text("加联系人") }
                Button(onClick = {
                    val size = formData.list("contacts").size
                    if (size > 0) formData.list("contacts").removeAt(size - 1)
                }) { Text("删最后一个") }
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        // ---------------- 右：渲染 + 数据 ----------------
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                if (error != null) {
                    ErrorPanel(error)
                } else {
                    DynamicLayout(
                        xml = xmlText,
                        data = formData,
                        config = DEMO_CONFIG,
                        actions = actionSet,
                        modifier = Modifier.fillMaxSize(),
                        registry = registry,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            Column(modifier = Modifier.fillMaxWidth().height(220.dp).padding(12.dp)) {
                Text("数据快照 formData.asState()", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = JsonCodec.encode(snapshot),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(error: Throwable) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("XML / 运行时错误", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = error.message ?: error.toString(),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 13.sp,
            )
        }
    }
}
