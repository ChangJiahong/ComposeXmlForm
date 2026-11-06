package cn.changjiahong.composexmlui.node

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import cn.changjiahong.composexmlui.binding.ActionRef
import cn.changjiahong.composexmlui.parser.NodeContext

/**
 * 叶子组件节点。
 *
 * 两个构造入口，指向同一份字段：
 * - **主构造**：属性值直接传入。节点因此可以脱离 XML 构造（测试、预览、代码里拼界面）。
 *   类型由字段声明决定，`AttrValue<Int>` 就是 Int，看不到任何转换器。
 * - **`(NodeContext)` 次构造**：从 XML 读，把每个属性在这个类的顶部一次性读出来。
 *   属性白名单也由这次读取反推（读了哪些名字，就支持哪些属性）。
 *
 * 两个入口之间没有逻辑重复：次构造只做「XML 名字 → 主构造参数」的搬运。
 */

/** 只读文本。`value` 必填，其余可选。 */
open class TextNode(
    val value: AttrValue<String>,
    val fontSize: AttrValue<TextUnit>? = null,
    val color: AttrValue<Color>? = null,
    val maxLines: AttrValue<Int>? = null,
    val fontWeight: AttrValue<FontWeight>? = null,
    val textAlign: AttrValue<TextAlign>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(context: NodeContext) : this(
        value = context.need("value"),
        fontSize = context.attr("fontSize"),
        color = context.attr("color"),
        maxLines = context.attr("maxLines"),
        fontWeight = context.attr("fontWeight"),
        textAlign = context.attr("textAlign"),
        style = BoxStyle(context),
        id = context.id,
        line = context.line,
    ) {
        context.ensureNoChildren()
    }
}

/** 按钮。动作是结构性的：注册期就要能校验「动作已注册」，所以不支持绑定。 */
open class ButtonNode(
    val text: AttrValue<String>,
    val action: ActionRef? = null,
    val enabled: AttrValue<Boolean>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(context: NodeContext) : this(
        text = context.need("text"),
        action = context.action("action"),
        enabled = context.attr("enabled"),
        style = BoxStyle(context),
        id = context.id,
        line = context.line,
    ) {
        context.ensureNoChildren()
    }
}

/** 文本输入。值是双向绑定：必须是 `{{path}}`，字面量在解析期就被拒绝。 */
open class TextFieldNode(
    val value: VarAttr<String>,
    val label: AttrValue<String>? = null,
    val placeholder: AttrValue<String>? = null,
    val readOnly: AttrValue<Boolean>? = null,
    val enabled: AttrValue<Boolean>? = null,
    val singleLine: AttrValue<Boolean>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(context: NodeContext) : this(
        value = context.needWritable("value"),
        label = context.attr("label"),
        placeholder = context.attr("placeholder"),
        readOnly = context.attr("readOnly"),
        enabled = context.attr("enabled"),
        singleLine = context.attr("singleLine"),
        style = BoxStyle(context),
        id = context.id,
        line = context.line,
    ) {
        context.ensureNoChildren()
    }
}

/**
 * 下拉选择。`options` / `value` 保留原始类型（`AttrValue<Any>`），不能转成 String：
 * `"1"` 与 `1` 在选中判断上不等价。
 *
 * 两者都要求 `{{path}}`：`options` 是一份数据列表，`value` 是选中项写回的目标。
 */
open class SelectNode(
    val options: VarAttr<Any>,
    val value: VarAttr<Any>? = null,
    val label: AttrValue<String>? = null,
    val placeholder: AttrValue<String>? = null,
    val enabled: AttrValue<Boolean>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(context: NodeContext) : this(
        options = context.needWritable("options"),
        value = context.writable("value"),
        label = context.attr("label"),
        placeholder = context.attr("placeholder"),
        enabled = context.attr("enabled"),
        style = BoxStyle(context),
        id = context.id,
        line = context.line,
    ) {
        context.ensureNoChildren()
    }
}
