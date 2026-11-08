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
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.api.ComponentModule
import cn.changjiahong.composexmlform.api.DynamicLayout
import cn.changjiahong.composexmlform.api.actions
import cn.changjiahong.composexmlform.api.install
import cn.changjiahong.composexmlform.api.rememberBindableData
import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.core.JsonCodec
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.StyledNode
import cn.changjiahong.composexmlform.node.ValAttr
import cn.changjiahong.composexmlform.node.VarAttr
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.renderer.LocalRenderDiagnostics
import cn.changjiahong.composexmlform.renderer.NodeRenderer
import cn.changjiahong.composexmlform.renderer.containerModifier
import cn.changjiahong.composexmlform.renderer.current
import cn.changjiahong.composexmlform.renderer.get
import cn.changjiahong.composexmlform.renderer.provideDelegate
import cn.changjiahong.composexmlform.renderer.toColor
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 三端统一的测试台。
 *
 * 同一份代码按可用宽度自适应：
 * - 宽屏（≥ [WIDE_BREAKPOINT]）：三栏并置 —— 源码 / 预览 / 数据，互不遮挡。
 * - 窄屏：Material 风格的单页三标签，左右滑动切页；提交动作自动跳到数据页。
 *
 * 数据只有一份 [BindableData]：预览里改输入框会写回数据页，数据页改 JSON 会推回预览，
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
            <Button text="退出登录" action="logout()" width="fill" />
        </Then>
        <Else>
            <Text value="状态：未登录" color="#C0392B" />
            <Button text="登录" action="login()" width="fill" />
        </Else>
    </If>

    <Text value="联系人" fontSize="16" fontWeight="bold" />
    <Foreach items="{{contacts}}" item="item" key="id">
        <Column spacing="8" padding="12" backgroundColor="#F3F4F6">
            <Text value="{{item.name}}" fontWeight="bold" />
            <TextField label="电话" value="{{item.phone}}" />
        </Column>
    </Foreach>

    <RatingBar label="评分（自定义组件）" value="{{rating}}" max="5" />

    <Button text="提交并查看数据" action="submit()" width="fill" />
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

// ---------------- 演示组件：RatingBar ----------------

/**
 * 自定义组件示例：新增组件不需要改动引擎核心。
 *
 * 属性声明在类体里（读过的才算「支持」），需要尺寸 / padding 就继承 [StyledNode]。
 */
class RatingBarNode(attrs: Attrs = Attrs.Empty) : StyledNode(attrs) {

    /** 评分要写回数据，所以是 [VarAttr]：字面量在解析期就被拒；不写则退化为局部 state。 */
    val value: VarAttr<Int>? = attrs.varOf("value")
    val max: ValAttr<Int>? = attrs.valOf("max")
    val label: ValAttr<String>? = attrs.valOf("label")
    val color: ValAttr<String>? = attrs.valOf("color")
}

// NodeRenderer 是 fun interface：直接用 object 实现，不需要（也不能）带构造括号。
object RatingBarRenderer : NodeRenderer<RatingBarNode> {

    @Composable
    override fun Render(node: RatingBarNode) {
        var value by node.value
        val max = node.max.get(5)
        val current = value ?: 0
        val activeColor = node.color?.current().toColor()
            .takeIf { it != Color.Unspecified } ?: Color(0xFFF5A623)
        val label = node.label?.current()

        Column(modifier = node.style.containerModifier()) {
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
                            .clickable { value = index + 1 }
                            .padding(1.dp),
                    )
                }
            }
        }
    }
}

val DemoComponents: ComponentModule = ComponentModule { registry ->
    registry.leaf("RatingBar", ::RatingBarNode, RatingBarRenderer)
}

// ---------------- 入口 ----------------

private val WIDE_BREAKPOINT = 840.dp

private const val PAGE_CODE = 0
private const val PAGE_PREVIEW = 1
private const val PAGE_DATA = 2

private val PAGE_TITLES = listOf("XML 源码", "实时预览", "数据快照")

@Composable
fun Playground() {
    val data = rememberBindableData(playgroundInitialData())
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
        CompositionLocalProvider(LocalRenderDiagnostics provides reporter) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                if (maxWidth >= WIDE_BREAKPOINT) {
                    WidePlayground(data, registry, xmlText, { xmlText = it }, logs)
                } else {
                    CompactPlayground(data, registry, xmlText, { xmlText = it }, logs)
                }
            }
        }
    }
}

// ---------------- 宽屏：三栏并置 ----------------

@Composable
private fun WidePlayground(
    data: BindableData,
    registry: EngineRegistry,
    xmlText: String,
    onXmlChange: (String) -> Unit,
    logs: List<String>,
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
    data: BindableData,
    registry: EngineRegistry,
    xmlText: String,
    onXmlChange: (String) -> Unit,
    logs: List<String>,
) {
    val pager = rememberPagerState(initialPage = PAGE_PREVIEW, pageCount = { PAGE_TITLES.size })
    val scope = rememberCoroutineScope()

    // 提交动作直接在 handler 里挂起跳到数据页：handler 本身就是 suspend 的，
    // 不需要额外 launch 一层。
    val actionSet = remember(data, pager) {
        actions {
            on("login") { data.set("isLogin", true) }
            on("logout") { data.set("isLogin", false) }
            on("submit") {
                data.set("submitCount", data.getInt("submitCount") + 1)
                pager.animateScrollToPage(PAGE_DATA)
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
    data: BindableData,
    registry: EngineRegistry,
    actionSet: Actions,
    xmlText: String,
    modifier: Modifier = Modifier,
) {
    // 渲染前先校验：解析 / 属性 / 结构 / 动作注册，任何错误都以面板形式展示，不炸 composition。
    val error = remember(xmlText, registry, actionSet) {
        runCatching { DynamicLayoutRuntime(xmlText, data, PLAYGROUND_CONFIG, actionSet, registry) }
            .onSuccess { it.dispose() }
            .exceptionOrNull()
    }

    Box(modifier.padding(16.dp)) {
        if (error != null) {
            ErrorPanel(error, Modifier.fillMaxSize())
        } else {
            DynamicLayout(
                xml = xmlText,
                data = data,
                config = PLAYGROUND_CONFIG,
                actions = actionSet,
                modifier = Modifier.fillMaxSize(),
                registry = registry,
            )
        }
    }
}

@Composable
private fun DataPanel(
    data: BindableData,
    logs: List<String>,
    modifier: Modifier = Modifier,
) {
    // 整表快照：预览里任何一次输入写回都会让它变，数据页因此永远和界面同源。
    val snapshot by data.asState()
    val json = remember(snapshot) { JsonCodec.encode(snapshot) }

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
        runCatching { data.applyJson(draft) }
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
            PillButton(text = "加联系人", onClick = {
                data.list("contacts").add(
                    mapOf(
                        "id" to Random.nextInt(1000, 9999),
                        "name" to "新联系人",
                        "phone" to "13800000000",
                    ),
                )
            })
            PillButton(text = "删末尾", onClick = {
                val contacts = data.list("contacts")
                if (contacts.size > 0) contacts.removeAt(contacts.size - 1)
            })
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
private fun ErrorPanel(error: Throwable, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("XML / 运行时错误", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = error.message ?: error.toString(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
