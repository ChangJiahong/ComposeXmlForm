package cn.changjiahong.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import cn.changjiahong.composexmlform.api.DynamicLayout
import cn.changjiahong.composexmlform.api.actions
import cn.changjiahong.composexmlform.api.rememberData

/**
 * 跨平台示例：Android / iOS / Desktop 共用同一份 XML 与数据。
 */
private const val FORM_XML = """
<Column scroll="true" padding="20" spacing="12" width="fill">
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
            <Button text="退出登录" onClick="@{ action.logout() }" />
        </Then>
        <Else>
            <Text value="状态：未登录" color="#C0392B" />
            <Button text="登录" onClick="@{ action.login() }" />
        </Else>
    </If>

    <Text value="联系人" fontSize="16" fontWeight="bold" />
    <Foreach source="{{data.contacts}}" item="item" key="id">
        <Column spacing="8" padding="12" backgroundColor="#F3F4F6">
            <Text value="{{item.name}}" fontWeight="bold" />
            <TextField label="电话" value="{{item.phone}}" />
        </Column>
    </Foreach>

    <Button text="提交" onClick="@{ action.submit() }" width="fill" />
</Column>
"""

private fun formData(): Map<String, Any?> = mapOf(
    "username" to "张三",
    "region" to "BJ",
    "isLogin" to true,
    "contacts" to listOf(
        mapOf("id" to 1, "name" to "李雷", "phone" to "13800000001"),
        mapOf("id" to 2, "name" to "韩梅梅", "phone" to "13800000002"),
    ),
)

private fun formConfig(): Map<String, Any?> = mapOf(
    "regions" to listOf(
        mapOf("label" to "北京", "value" to "BJ"),
        mapOf("label" to "上海", "value" to "SH"),
        mapOf("label" to "广州", "value" to "GZ"),
    ),
)

@Composable
@Preview
fun App() {
    val data = rememberData(formData())
    val actions = remember {
        actions {
            on("submit") { println("[ComposeXmlUI] submit") }
            on("login") { println("[ComposeXmlUI] login") }
            on("logout") { println("[ComposeXmlUI] logout") }
        }
    }
    MaterialTheme {
        DynamicLayout(
            xml = FORM_XML,
            data = data,
            config = formConfig(),
            actions = actions,
        )
    }
}
