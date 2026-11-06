package cn.changjiahong.composexmlui.node

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import cn.changjiahong.composexmlui.binding.ActionRef

/**
 * 叶子组件节点。
 *
 * 两个构造入口，指向同一份字段：
 * - **主构造**：属性值直接传入，且**每个属性都有默认值**。节点因此可以脱离 XML 构造
 *   （测试、预览、代码里拼界面），也可以只用 `TextNode()` 造一个空文本。
 * - **`(Attrs)` 次构造**：从 XML 读。次构造只做「XML 名字 → 主构造参数」的搬运，
 *   白名单也由这次读取反推（读了哪些名字，就支持哪些属性）。
 *
 * 缺属性不是错误：`<Text/>` 就是空文本，`<TextField/>` 就是非受控输入框。
 * 真正无声的错是**写错属性名**，那由白名单在解析期拦住。
 */

/** 只读文本。 */
open class TextNode(
    val value: ValAttr<String> = valAttr(""),
    val fontSize: ValAttr<TextUnit>? = null,
    val color: ValAttr<Color>? = null,
    val maxLines: ValAttr<Int>? = null,
    val fontWeight: ValAttr<FontWeight>? = null,
    val textAlign: ValAttr<TextAlign>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(attrs: Attrs) : this(
        value = attrs.valOf("value") ?: valAttr(""),
        fontSize = attrs.valOf("fontSize"),
        color = attrs.valOf("color"),
        maxLines = attrs.valOf("maxLines"),
        fontWeight = attrs.valOf("fontWeight"),
        textAlign = attrs.valOf("textAlign"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}

/** 按钮。动作是结构性的：注册期就要能校验「动作已注册」，所以不支持绑定。 */
open class ButtonNode(
    val text: ValAttr<String> = valAttr(""),
    val action: ActionRef? = null,
    val enabled: ValAttr<Boolean>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(attrs: Attrs) : this(
        text = attrs.valOf("text") ?: valAttr(""),
        action = attrs.action("action"),
        enabled = attrs.valOf("enabled"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}

/**
 * 文本输入。`value` 是唯一的数据槽位，所以它是 [VarAttr]：
 *
 * - 写了 `value="{{username}}"`：双向绑定，输入写回数据；
 * - 没写 `value`：**非受控输入框**，渲染器给一个局部 state，
 *   打字、清空都正常，只是不回写任何数据。
 *
 * 这个退化不需要额外机制：`value == null` 就是「没有绑定」，渲染器用
 * `asStateOr("")` 拿到一个自持句柄即可。
 */
open class TextFieldNode(
    val value: VarAttr<String>? = null,
    val label: ValAttr<String>? = null,
    val placeholder: ValAttr<String>? = null,
    val readOnly: ValAttr<Boolean>? = null,
    val enabled: ValAttr<Boolean>? = null,
    val singleLine: ValAttr<Boolean>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(attrs: Attrs) : this(
        value = attrs.varOf("value"),
        label = attrs.valOf("label"),
        placeholder = attrs.valOf("placeholder"),
        readOnly = attrs.valOf("readOnly"),
        enabled = attrs.valOf("enabled"),
        singleLine = attrs.valOf("singleLine"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}

/**
 * 下拉选择。`options` / `value` 保留原始类型（`ValAttr<Any>` / `VarAttr<Any>`），不能转成 String：
 * `"1"` 与 `1` 在选中判断上不等价。
 *
 * `options` 是数据（只读），`value` 是写回目标（可写）。两者都没写时，
 * 就是一个空下拉框 + 非受控选中项。
 */
open class SelectNode(
    val options: ValAttr<Any> = valAttr(emptyList<Any>()),
    val value: VarAttr<Any>? = null,
    val label: ValAttr<String>? = null,
    val placeholder: ValAttr<String>? = null,
    val enabled: ValAttr<Boolean>? = null,
    override val style: BoxStyle = BoxStyle(),
    id: String? = null,
    line: Int = 0,
) : Node(id, line), Styled {

    constructor(attrs: Attrs) : this(
        options = attrs.valOf("options") ?: valAttr(emptyList<Any>()),
        value = attrs.varOf("value"),
        label = attrs.valOf("label"),
        placeholder = attrs.valOf("placeholder"),
        enabled = attrs.valOf("enabled"),
        style = BoxStyle(attrs),
        id = attrs.id,
        line = attrs.line,
    )
}
