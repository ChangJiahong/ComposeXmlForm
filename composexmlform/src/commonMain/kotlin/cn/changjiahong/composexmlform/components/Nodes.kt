package cn.changjiahong.composexmlform.components

import cn.changjiahong.composexmlform.api.decl.Props
import cn.changjiahong.composexmlform.api.decl.PropsContainer
import cn.changjiahong.composexmlform.api.decl.PropsNode

/**
 * 内置组件节点。
 *
 * **一行一条属性，声明与读取是同一行**：`val text by props.string("value", "")`
 * 同时完成三件事 —— 属性表（名字 / 类型 / 默认值 / 可写性）、渲染期读值、
 * 需要时写回。属性表由注册时「用空 [Props] 构造一次」收集，所以不存在第二份清单。
 *
 * 两条约定：
 * - **带 default 的返回非空类型，不带的返回可空类型**。`<Text/>` 没写 `value`
 *   就是空串（不是「没有文本」），所以 `text` 声明成非空；`color` 没写就是「用主题默认」，
 *   声明成可空。类型本身就是语义，不需要另外查一个 `present`。
 * - 枚举型属性（`arrangement` / `alignment` / `fontWeight`）一律声明成 `String`：
 *   Compose 的排版类型不该进节点定义，映射留在渲染器。
 *
 * 节点不 import `env`、不 import Compose：它们只描述界面，值在渲染层才求出来。
 */

/** 只读文本。XML 属性名是 `value`，字段名取 `text` 更贴渲染语义。 */
class TextNode(props: Props) : PropsNode(props) {

    val text by props.string("value", "")

    val fontSize by props.float("fontSize")

    val color by props.string("color")

    val fontWeight by props.string("fontWeight")

    val textAlign by props.string("textAlign")

    val maxLines by props.long("maxLines")

    val style = props.style()
}

/**
 * 按钮。
 *
 * `onClick` 是**普通事件属性**（`props.action`），不是框架特例：
 * 任何节点声明一个 `props.action("onXxx")`，就获得了完全一样的能力
 * （白名单、诊断坐标、`@{}` 语法校验、渲染层的事件现场捕获）。
 */
class ButtonNode(props: Props) : PropsNode(props) {

    val text by props.string("text", "")

    val onClick by props.action("onClick")

    val enabled by props.bool("enabled", true)

    val style = props.style()
}

/**
 * 文本输入。`value` 是唯一的数据属性，所以它是**可写属性**（`props.varString`）：
 *
 * - 写了 `value="{{data.username}}"`：双向绑定，输入写回数据；
 * - 没写 `value`：非受控输入框，直接 `node.value = …` 只改本行局部 state。
 *
 * 这个退化不需要额外机制：`boundPath == null` 就是「没有绑定」。
 */
class TextFieldNode(props: Props) : PropsNode(props) {

    var value by props.varString("value", "")

    val label by props.string("label")

    val placeholder by props.string("placeholder")

    val readOnly by props.bool("readOnly", false)

    val enabled by props.bool("enabled", true)

    val singleLine by props.bool("singleLine", true)

    val style = props.style()
}

/**
 * 下拉选择。
 *
 * `options` / `value` 保留原始类型（`props.value` / `props.varValue`），不能收敛成 String：
 * `"1"` 与 `1` 在选中判断上不等价。
 */
class SelectNode(props: Props) : PropsNode(props) {

    val options by props.value("options")

    var value by props.varValue("value")

    val label by props.string("label")

    val placeholder by props.string("placeholder", "请选择")

    val enabled by props.bool("enabled", true)

    val style = props.style()
}

/** 纵向布局。 */
class ColumnNode(props: Props) : PropsContainer(props) {

    val scroll by props.bool("scroll", false)

    val spacing by props.float("spacing")

    val arrangement by props.string("arrangement")

    val horizontalAlignment by props.string("horizontalAlignment")

    val style = props.style()
}

/** 横向布局。 */
class RowNode(props: Props) : PropsContainer(props) {

    val scroll by props.bool("scroll", false)

    val spacing by props.float("spacing")

    val arrangement by props.string("arrangement")

    val verticalAlignment by props.string("verticalAlignment")

    val style = props.style()
}

/** 层叠布局。 */
class BoxNode(props: Props) : PropsContainer(props) {

    val contentAlignment by props.string("contentAlignment")

    val style = props.style()
}
