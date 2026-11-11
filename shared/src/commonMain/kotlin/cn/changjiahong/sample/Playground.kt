package cn.changjiahong.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.changjiahong.composexmlform.ComposeXmlForm
import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.ComponentModule
import cn.changjiahong.composexmlform.api.DynamicLayout
import cn.changjiahong.composexmlform.api.EngineRegistry
import cn.changjiahong.composexmlform.api.actions
import cn.changjiahong.composexmlform.api.getInt
import cn.changjiahong.composexmlform.api.list
import cn.changjiahong.composexmlform.api.patchJson
import cn.changjiahong.composexmlform.api.rememberData
import cn.changjiahong.composexmlform.api.reset
import cn.changjiahong.composexmlform.api.set
import cn.changjiahong.composexmlform.api.toJson
import cn.changjiahong.composexmlform.api.decl.Props
import cn.changjiahong.composexmlform.api.decl.PropsNode
import cn.changjiahong.composexmlform.api.decl.component
import cn.changjiahong.composexmlform.components.style
import cn.changjiahong.composexmlform.components.toColor
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.render.DiagnosticSink
import cn.changjiahong.composexmlform.engine.value.DataObject
import kotlinx.coroutines.launch

/**
 * 三端统一的测试台。
 *
 * 同一份代码按可用宽度自适应：
 * - 宽屏（≥ [WIDE_BREAKPOINT]）：三栏并置 —— 源码 / 预览 / 数据，互不遮挡。
 * - 窄屏：Material 风格的单页三标签，左右滑动切页；提交动作自动跳到数据页。
 *
 * 数据只有一份 [DataObject]：预览里改输入框会写回数据页，数据页改 JSON 会推回预览，
 * 两侧看的是同一个数据根。
 *
 * 与 [App] 的区别：[App] 是最小示例（动作只打日志），这里是可交互的调试台。
 */

// ---------------- 演示资源 ----------------

private val PLAYGROUND_XML = """
<Column scroll="true" padding="20" spacing="12" width="fill">
    <Text value="账号登录" fontSize="22" fontWeight="bold" />

    <Row spacing="6" verticalAlignment="center">
        <Text value="当前用户：" color="#666666" />
        <Text value="{{username}}" fontWeight="bold" />
    </Row>

    <TextField label="姓名" placeholder="请输入姓名" value="{{username}}" />
    <Select label="城市" options="{{config.regions}}" value="{{region}}" placeholder="请选择城市" />

    <If test="{{isLogin}}">
        <Then>
            <Text value="状态：已登录" color="#1B7F3B" />
            <Button text="退出登录" onClick="@{ action.logout() }" width="fill" />
        </Then>
        <Else>
            <Text value="状态：未登录" color="#C0392B" />
            <Button text="登录" onClick="@{ action.login() }" width="fill" />
        </Else>
    </If>

    <Text value="联系人" fontSize="16" fontWeight="bold" />
    <!-- 增删都是表达式：new() 造空对象、append 追加、remove(i) 按下标删。
         不需要注册 action，也不需要先在别处定义函数。 -->
    <Row spacing="8">
        <Button text="添加联系人" onClick="@{ data.contacts.append(new()) }" />
        <Button text="删除末尾" onClick="@{ data.contacts.remove(data.contacts.size() - 1) }" />
    </Row>
    <!-- 没写 key：new() 建出来的行没有 id，混用 id 与下标做 key 反而更难预料 -->
    <Foreach source="{{data.contacts}}" item="item" index="i">
        <Column spacing="8" padding="12" backgroundColor="#F3F4F6">
            <Row spacing="8" verticalAlignment="center">
                <Text value="{ item.name ?: '（新联系人）' }" fontWeight="bold" />
                <!-- i 是这一行的下标 -->
                <Button text="删除" onClick="@{ data.contacts.remove(i) }" />
            </Row>
            <TextField label="电话" value="{{item.phone}}" />
        </Column>
    </Foreach>

    <RatingBar label="评分（自定义组件）" value="{{rating}}" max="5" />

    <Button text="提交并查看数据" onClick="@{ action.submit() }" width="fill" />
</Column>
""".trimIndent()

private val PLAYGROUND_CONFIG: Map<String, Any?> = mapOf(
    "regions" to listOf(
        mapOf("label" to "北京", "value" to "BJ"),
        mapOf("label" to "上海", "value" to "SH"),
        mapOf("label" to "广州", "value" to "GZ"),
    ),
)

private fun playgroundInitialData(): Map<String, Any?> = mapOf(
    "username" to "张三",
    "region" to "BJ",
    // 初始未登录：一进来就能看到「登录」按钮，方便验证动作链路。
    "isLogin" to false,
    "rating" to 3,
    "submitCount" to 0,
    "contacts" to listOf(
        mapOf("id" to 1, "name" to "李雷", "phone" to "13800000001"),
        mapOf("id" to 2, "name" to "韩梅梅", "phone" to "13800000002"),
    ),
)

/**
 * 追加一条联系人。**只给数据页用** —— 模板里的「添加联系人」是
 * `@{ data.contacts.append(new()) }`，走表达式，不经过这里。
 *
 * 数据页要带 id 是因为它在 Kotlin 侧，能算出「现有最大 id + 1」；模板里算不了
 * （没有 `maxOf`，`ExpressionsOnly` 也不许声明变量），所以模板那侧的行没有 id。
 */
private fun DataObject.addContact() {
    val contacts = list("contacts")
    val maxId = (0 until contacts.size)
        .mapNotNull { (contacts[it] as? DataObject)?.member("id") as? Long }
        .maxOrNull() ?: 0L
    val next = maxId + 1
    contacts.add(
        mapOf(
            "id" to next,
            "name" to "联系人 $next",
            "phone" to "139" + next.toString().padStart(8, '0'),
        ),
    )
}

// ---------------- 演示组件：RatingBar ----------------

/**
 * 自定义组件示例：新增组件不需要改动引擎核心。
 *
 * **全部代码就是这三段**：属性一行一个、一个 Composable、一行注册。
 * 属性名 / 类型 / 默认值 / 可写性都在构造函数里，没有 `*Attrs` 对象、
 * 没有 `create` lambda 里的 `attrs.attr(...)`、没有 `NodeRenderer` 实现类。
 */
class RatingBarNode(props: Props) : PropsNode(props) {

    /** 评分要写回数据，所以是**可写属性**：只接受 `{{path}}`，不写则退化为本行局部 state。 */
    var value by props.varLong("value", 0L)

    val max by props.long("max", 5L)

    val label by props.string("label")

    val color by props.string("color")

    val style = props.style()
}

@Composable
fun RatingBar(node: RatingBarNode) {
    val max = node.max.toInt()
    val current = node.value.toInt()
    val activeColor = node.color.toColor()
        .takeIf { it != Color.Unspecified } ?: Color(0xFFF5A623)
    val label = node.label

    Column(modifier = node.style.modifier()) {
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
                        .clickable { node.value = (index + 1).toLong() }
                        .padding(1.dp),
                )
            }
        }
    }
}

val DemoComponents: ComponentModule = ComponentModule { registry ->
    registry.component("RatingBar", create = ::RatingBarNode, render = ::RatingBar)
}

// ---------------- 入口 ----------------

private val WIDE_BREAKPOINT = 840.dp

private const val PAGE_CODE = 0
private const val PAGE_PREVIEW = 1
private const val PAGE_DATA = 2

private val PAGE_TITLES = listOf("XML 源码", "实时预览", "数据快照")

@Composable
fun Playground() {
    val data = rememberData(playgroundInitialData())
    val registry = remember { ComposeXmlForm.createRegistry { install(DemoComponents) } }

    var xmlText by remember { mutableStateOf(PLAYGROUND_XML) }

    // 渲染期诊断（动作失败、非法属性、节点渲染失败）默认被静默丢弃，
    // 调试台必须把它接出来，否则「点了没反应」永远查不到原因。
    val logs = remember { mutableStateListOf<String>() }
    val reporter: (Diagnostic) -> Unit = remember(logs) {
        { diagnostic ->
            logs.add(0, "${diagnostic.severity} ${diagnostic.code}: ${diagnostic.message}")
            if (logs.size > 60) logs.removeAt(logs.lastIndex)
        }
    }

    MaterialTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (maxWidth >= WIDE_BREAKPOINT) {
                WidePlayground(data, registry, xmlText, { xmlText = it }, logs, reporter)
            } else {
                CompactPlayground(data, registry, xmlText, { xmlText = it }, logs, reporter)
            }
        }
    }
}

// ---------------- 宽屏：三栏并置 ----------------

@Composable
private fun WidePlayground(
    data: DataObject,
    registry: EngineRegistry,
    xmlText: String,
    onXmlChange: (String) -> Unit,
    logs: List<String>,
    sink: DiagnosticSink,
) {
    var showCode by remember { mutableStateOf(true) }

    val actionSet = remember(data) {
        actions {
            on("login") { data.set("isLogin", true) }
            on("logout") { data.set("isLogin", false) }
            on("submit") { data.set("submitCount", data.getInt("submitCount") + 1) }
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (showCode) {
            CodePanel(
                xmlText = xmlText,
                onXmlChange = onXmlChange,
                modifier = Modifier.width(420.dp).fillMaxHeight(),
                onCollapse = { showCode = false },
            )
            VerticalDivider()
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            PreviewPanel(
                data = data,
                registry = registry,
                actionSet = actionSet,
                xmlText = xmlText,
                modifier = Modifier.fillMaxSize(),
                sink = sink,
            )
            if (!showCode) {
                PillButton(
                    text = "</> 源码",
                    onClick = { showCode = true },
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
        }

        VerticalDivider()
        DataPanel(data, logs, Modifier.width(360.dp).fillMaxHeight())
    }
}

// ---------------- 窄屏：单页三标签 ----------------

@Composable
private fun CompactPlayground(
    data: DataObject,
    registry: EngineRegistry,
    xmlText: String,
    onXmlChange: (String) -> Unit,
    logs: List<String>,
    sink: DiagnosticSink,
) {
    val pager = rememberPagerState(initialPage = PAGE_PREVIEW, pageCount = { PAGE_TITLES.size })
    val scope = rememberCoroutineScope()

    // 动作是**同步**的（只负责改数据）；跳页这种挂起操作要自己 launch。
    val actionSet = remember(data, pager) {
        actions {
            on("login") { data.set("isLogin", true) }
            on("logout") { data.set("isLogin", false) }
            on("submit") {
                data.set("submitCount", data.getInt("submitCount") + 1)
                scope.launch { pager.animateScrollToPage(PAGE_DATA) }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        CompactTopBar(title = PAGE_TITLES[pager.currentPage])

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                PAGE_CODE -> CodePanel(
                    xmlText = xmlText,
                    onXmlChange = onXmlChange,
                    modifier = Modifier.fillMaxSize(),
                )

                PAGE_PREVIEW -> PreviewPanel(
                    data = data,
                    registry = registry,
                    actionSet = actionSet,
                    xmlText = xmlText,
                    modifier = Modifier.fillMaxSize(),
                    sink = sink,
                )

                else -> DataPanel(data, logs, Modifier.fillMaxSize())
            }
        }

        SegmentTabs(
            selected = pager.currentPage,
            onSelect = { index -> scope.launch { pager.animateScrollToPage(index) } },
        )
    }
}

@Composable
private fun CompactTopBar(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ComposeXmlForm",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = title,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SegmentTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PAGE_TITLES.forEachIndexed { index, title ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- 三个面板（宽窄屏共用） ----------------

@Composable
private fun CodePanel(
    xmlText: String,
    onXmlChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
) {
    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("XML 源码", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            PillButton(text = "重置", onClick = { onXmlChange(PLAYGROUND_XML) })
            if (onCollapse != null) {
                Spacer(Modifier.width(8.dp))
                PillButton(text = "收起", onClick = onCollapse)
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = xmlText,
            onValueChange = onXmlChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "改动即时生效，预览与数据同步刷新",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewPanel(
    data: DataObject,
    registry: EngineRegistry,
    actionSet: Actions,
    xmlText: String,
    modifier: Modifier = Modifier,
    sink: DiagnosticSink,
) {
    // 渲染前先解析：标签 / 属性 / 语法 / 全局对象名的问题都在这一步暴露。
    // 有错误就只画诊断面板 —— 残缺的树渲染出来只会掩盖真正的原因。
    val diagnostics = remember(xmlText, registry) {
        ComposeXmlForm.parse(xmlText, data, PLAYGROUND_CONFIG, registry = registry).diagnostics
    }
    val hasError = diagnostics.any { it.isError }

    Box(modifier.padding(16.dp)) {
        if (hasError) {
            ErrorPanel(diagnostics, Modifier.fillMaxSize())
        } else {
            DynamicLayout(
                xml = xmlText,
                data = data,
                config = PLAYGROUND_CONFIG,
                actions = actionSet,
                modifier = Modifier.fillMaxSize(),
                registry = registry,
                diagnosticSink = sink,
            )
        }
    }
}

@Composable
private fun DataPanel(
    data: DataObject,
    logs: List<String>,
    modifier: Modifier = Modifier,
) {
    // 整表快照：`toJson()` 会读全部成员，于是订阅全部成员 —— 预览里任何一次输入写回
    // 都会让它重算，数据页因此永远和界面同源。
    val json = data.toJson()

    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    // 编辑态与只读态共用同一块区域：进入编辑只是把这段文本本身换成可输入，
    // 不再另起一个输入框插到下面，诊断区不会因为点一次「编辑」被挤走。
    val beginEdit = {
        draft = json
        editError = null
        editing = true
    }
    val commitEdit: () -> Unit = {
        runCatching { data.patchJson(draft) }
            .onSuccess {
                editing = false
                editError = null
            }
            .onFailure { editError = it.message ?: "JSON 解析失败" }
    }

    Column(
        modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("数据快照", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = "提交 ${data.getInt("submitCount")} 次",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(text = "加联系人", onClick = { data.addContact() })
            PillButton(text = "重置数据", onClick = {
                data.reset(playgroundInitialData())
                editing = false
                editError = null
            })
        }

        Spacer(Modifier.height(12.dp))

        // 只读与编辑共用这一块：字体、行高、内边距完全一致，切换时不跳高度。
        val editorStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (editing) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    else Color.Transparent,
                )
                .then(
                    if (editing) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp),
                        )
                    } else {
                        // 只读态整块可点，点哪进哪，不需要先把视线移到按钮上。
                        Modifier.clickable { beginEdit() }
                    },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        editError = null
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    textStyle = editorStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            } else {
                Text(text = json, style = editorStyle)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 动作行高度固定：只读时是「编辑」，编辑时换成「应用 / 取消」，位置不变。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editing) {
                PillButton(text = "应用", onClick = commitEdit)
                PillButton(text = "取消", onClick = {
                    editing = false
                    editError = null
                })
                Spacer(Modifier.weight(1f))
                Text(
                    text = "改完点应用，写回预览",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                PillButton(text = "编辑", onClick = beginEdit)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "点文本也可直接编辑",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        editError?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        HorizontalRule()
        Spacer(Modifier.height(10.dp))

        Text("运行时诊断", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (logs.isEmpty()) {
            Text(
                text = "暂无。动作失败 / 属性非法 / 渲染失败会出现在这里。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            logs.take(8).forEach { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ---------------- 小工具 ----------------

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun HorizontalRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ErrorPanel(diagnostics: List<Diagnostic>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("XML / 解析错误", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = diagnostics.joinToString("\n") { "${it.severity} ${it.code}：${it.message}" },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
