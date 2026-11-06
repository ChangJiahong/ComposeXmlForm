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
 * 属性在属性初始化处直接从 [NodeContext] 读取：**类型由左边的 `AttrValue<T>` 决定**，
 * 转换规则由 T 自动推导，所以这里看不到任何转换器：
 * ```
 * val maxLines: AttrValue<Int>? = context.attr("maxLines")   // "3" → 3
 * ```
 * 构造函数因此只需要一个 `context`，注册表写 `::TextNode` 即可。
 */

/** 只读文本。`value` 必填，其余可选。 */
open class TextNode(context: NodeContext) : Node(context), Styled by BoxStyle(context) {

    val value: AttrValue<String> = context.need("value")
    val fontSize: AttrValue<TextUnit>? = context.attr("fontSize")
    val color: AttrValue<Color>? = context.attr("color")
    val maxLines: AttrValue<Int>? = context.attr("maxLines")
    val fontWeight: AttrValue<FontWeight>? = context.attr("fontWeight")
    val textAlign: AttrValue<TextAlign>? = context.attr("textAlign")

    init {
        context.ensureNoChildren()
    }
}

/** 按钮。动作是结构性的：注册期就要能校验「动作已注册」，所以不支持绑定。 */
open class ButtonNode(context: NodeContext) : Node(context), Styled by BoxStyle(context) {

    val text: AttrValue<String> = context.need("text")
    val action: ActionRef? = context.action("action")
    val enabled: AttrValue<Boolean>? = context.attr("enabled")

    init {
        context.ensureNoChildren()
    }
}

/** 文本输入。值是双向绑定：必须是 `{{path}}`，字面量在解析期就被拒绝。 */
open class TextFieldNode(context: NodeContext) : Node(context), Styled by BoxStyle(context) {

    val value: AttrValue<String> = context.needWritable("value")
    val label: AttrValue<String>? = context.attr("label")
    val placeholder: AttrValue<String>? = context.attr("placeholder")
    val readOnly: AttrValue<Boolean>? = context.attr("readOnly")
    val enabled: AttrValue<Boolean>? = context.attr("enabled")
    val singleLine: AttrValue<Boolean>? = context.attr("singleLine")

    init {
        context.ensureNoChildren()
    }
}

/**
 * 下拉选择。`options` / `value` 保留原始类型（`AttrValue<Any>`），不能转成 String：
 * `"1"` 与 `1` 在选中判断上不等价。
 */
open class SelectNode(context: NodeContext) : Node(context), Styled by BoxStyle(context) {

    val options: AttrValue<Any> = context.needWritable("options")

    /** 可选：没有 value 时只展示 options，选中不回写。 */
    val value: AttrValue<Any>? = context.writable("value")
    val label: AttrValue<String>? = context.attr("label")
    val placeholder: AttrValue<String>? = context.attr("placeholder")
    val enabled: AttrValue<Boolean>? = context.attr("enabled")

    init {
        context.ensureNoChildren()
    }
}
