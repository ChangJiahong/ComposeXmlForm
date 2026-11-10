package cn.changjiahong.composexmlform.components

import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.node.ContainerNode
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 内置组件节点。
 *
 * **每个属性都是 [AttrSlot]**，没有「字段是 `AttrSlot` 还是裸值」的分裂 ——
 * 值从哪来（字面量 / 绑定 / 表达式）只有槽位知道，节点只负责声明「我需要哪几个属性」。
 *
 * 属性可以没有值：`<Text/>` 就是空文本，`<TextField/>` 就是非受控输入框。
 * 真正无声的错是**写错属性名**，那由 `AttrSet.finish()` 在解析期拦住。
 *
 * 节点不 import `env`、不 import Compose：它们只描述界面，值在渲染层才求出来。
 */

/** 只读文本。 */
class TextNode(
    id: String,
    val text: AttrSlot<String>,
    val fontSize: AttrSlot<Float>,
    val color: AttrSlot<String>,
    val fontWeight: AttrSlot<String>,
    val textAlign: AttrSlot<String>,
    val maxLines: AttrSlot<Long>,
    val style: BoxStyle,
) : Node(id)

/**
 * 按钮。
 *
 * `onClick` 是**普通函数槽位**（`AttrSlot<Function>`），不是框架特例：
 * 任何节点声明一个同类型字段 + 一行 `attrs.attr(SomeAction)`，就获得了完全一样的能力
 * （白名单、诊断坐标、`@{}` 语法校验、渲染层的事件现场捕获）。
 */
class ButtonNode(
    id: String,
    val text: AttrSlot<String>,
    val onClick: AttrSlot<Function>,
    val enabled: AttrSlot<Boolean>,
    val style: BoxStyle,
) : Node(id)

/**
 * 文本输入。`value` 是唯一的数据槽位，所以它是**可写槽位**（`Attr.varString`）：
 *
 * - 写了 `value="{{data.username}}"`：双向绑定，输入写回数据；
 * - 没写 `value`：非受控输入框，渲染器用局部 state，能打字只是不回写。
 *
 * 这个退化不需要额外机制：`boundPath == null` 就是「没有绑定」。
 */
class TextFieldNode(
    id: String,
    val value: AttrSlot<String>,
    val label: AttrSlot<String>,
    val placeholder: AttrSlot<String>,
    val readOnly: AttrSlot<Boolean>,
    val enabled: AttrSlot<Boolean>,
    val singleLine: AttrSlot<Boolean>,
    val style: BoxStyle,
) : Node(id)

/**
 * 下拉选择。
 *
 * `options` / `value` 保留原始类型（`AttrSlot<Value>`），不能收敛成 String：
 * `"1"` 与 `1` 在选中判断上不等价。
 */
class SelectNode(
    id: String,
    val options: AttrSlot<Value>,
    val value: AttrSlot<Value>,
    val label: AttrSlot<String>,
    val placeholder: AttrSlot<String>,
    val enabled: AttrSlot<Boolean>,
    val style: BoxStyle,
) : Node(id)

/** 纵向布局。枚举型属性声明成 `String`，到 Compose 类型的映射留在渲染器。 */
class ColumnNode(
    id: String,
    val scroll: AttrSlot<Boolean>,
    val spacing: AttrSlot<Float>,
    val arrangement: AttrSlot<String>,
    val horizontalAlignment: AttrSlot<String>,
    val style: BoxStyle,
    children: List<Node>,
) : ContainerNode(id, children)

/** 横向布局。 */
class RowNode(
    id: String,
    val scroll: AttrSlot<Boolean>,
    val spacing: AttrSlot<Float>,
    val arrangement: AttrSlot<String>,
    val verticalAlignment: AttrSlot<String>,
    val style: BoxStyle,
    children: List<Node>,
) : ContainerNode(id, children)

/** 层叠布局。 */
class BoxNode(
    id: String,
    val contentAlignment: AttrSlot<String>,
    val style: BoxStyle,
    children: List<Node>,
) : ContainerNode(id, children)
