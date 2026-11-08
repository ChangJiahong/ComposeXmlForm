package cn.changjiahong.composexmlform.node

import cn.changjiahong.composexmlform.binding.ActionRef

/**
 * 叶子组件节点。
 *
 * **一个构造：`(Attrs)`**。属性只在字段声明处写一次（名字 + 类型），
 * 不再需要「主构造参数 → 次构造搬运」的两份清单 —— 那两份清单在属性变多时
 * 必然出现不一致，而编译器对此无能为力。
 *
 * 缺属性不是错误：`<Text/>` 就是空文本，`<TextField/>` 就是非受控输入框。
 * 真正无声的错是**写错属性名**，那由白名单在解析期拦住（白名单正是从这些 `valOf` 反推的）。
 *
 * 程序化构造（测试 / 预览 / 代码里拼界面）走 [Attrs.Empty] 与 [Attrs.literal]：
 * ```
 * TextNode()                                                    // 全默认
 * TextNode(Attrs.literal("value" to "你好", "fontSize" to 14f))  // 直接给定型值
 * ```
 */

/** 只读文本。 */
open class TextNode(attrs: Attrs = Attrs.Empty) : StyledNode(attrs) {

    val value: ValAttr<String> = attrs.valOf("value") ?: valAttr("")

    /** 字号数值（单位 sp 由渲染器加）。 */
    val fontSize: ValAttr<Float>? = attrs.valOf("fontSize")
    val color: ValAttr<String>? = attrs.valOf("color")
    val maxLines: ValAttr<Int>? = attrs.valOf("maxLines")
    val fontWeight: ValAttr<String>? = attrs.valOf("fontWeight")
    val textAlign: ValAttr<String>? = attrs.valOf("textAlign")
}

/**
 * 按钮。
 *
 * `action` 是一个**普通属性槽位**（`ValAttr<ActionRef>`），不是框架特例：
 * 任何节点声明一个同类型字段 + 一行 `attrs.actionOf("…")`，就获得了和它完全一样的能力
 * （白名单、坐标、解析期语法校验、构造期「动作已注册」校验）。
 * 渲染器通过 `node.action.fire()` 拿到可点击函数。
 */
open class ButtonNode(attrs: Attrs = Attrs.Empty) : StyledNode(attrs) {

    val text: ValAttr<String> = attrs.valOf("text") ?: valAttr("")
    val action: ValAttr<ActionRef>? = attrs.actionOf("action")
    val enabled: ValAttr<Boolean>? = attrs.valOf("enabled")
}

/**
 * 文本输入。`value` 是唯一的数据槽位，所以它是 [VarAttr]：
 *
 * - 写了 `value="{{username}}"`：双向绑定，输入写回数据；
 * - 没写 `value`：**非受控输入框**，渲染器用 `var value by node.value` 拿到局部 state，
 *   打字、清空都正常，只是不回写任何数据。
 *
 * 这个退化不需要额外机制：`value == null` 就是「没有绑定」。
 */
open class TextFieldNode(attrs: Attrs = Attrs.Empty) : StyledNode(attrs) {

    val value: VarAttr<String>? = attrs.varOf("value")
    val label: ValAttr<String>? = attrs.valOf("label")
    val placeholder: ValAttr<String>? = attrs.valOf("placeholder")
    val readOnly: ValAttr<Boolean>? = attrs.valOf("readOnly")
    val enabled: ValAttr<Boolean>? = attrs.valOf("enabled")
    val singleLine: ValAttr<Boolean>? = attrs.valOf("singleLine")

    init {
        // 校验规则是**解析期**登记的：规则来自属性，而字段身份来自 value 的绑定路径。
        // 没有绑定就没有字段可校验，但属性仍要读掉（否则会被当成「不支持的属性」）。
        val required = attrs.valOf<Boolean>("required")?.constant ?: false
        val minLength = attrs.valOf<Int>("minLength")?.constant
        val maxLength = attrs.valOf<Int>("maxLength")?.constant
        val pattern = attrs.valOf<String>("pattern")?.constant

        val path = value?.path
        if (path != null) {
            attrs.recordFieldRule(
                path = path,
                label = label?.constant,
                required = required,
                minLength = minLength,
                maxLength = maxLength,
                pattern = pattern,
            )
        }
    }
}

/**
 * 下拉选择。`options` / `value` 保留原始类型（`ValAttr<Any>` / `VarAttr<Any>`），不能转成 String：
 * `"1"` 与 `1` 在选中判断上不等价。
 *
 * `options` 是数据（只读），`value` 是写回目标（可写）。两者都没写时，
 * 就是一个空下拉框 + 非受控选中项。
 */
open class SelectNode(attrs: Attrs = Attrs.Empty) : StyledNode(attrs) {

    val options: ValAttr<Any> = attrs.valOf("options") ?: valAttr(emptyList<Any>())
    val value: VarAttr<Any>? = attrs.varOf("value")
    val label: ValAttr<String>? = attrs.valOf("label")
    val placeholder: ValAttr<String>? = attrs.valOf("placeholder")
    val enabled: ValAttr<Boolean>? = attrs.valOf("enabled")
}
