package cn.changjiahong.composexmlform.components

import cn.changjiahong.composexmlform.api.ComponentModule
import cn.changjiahong.composexmlform.engine.node.ChildRule

/**
 * 内置组件注册。
 *
 * **一行一个组件**：`create` 是节点构造器（属性声明就在里面），`render` 是画法。
 * 属性表、节点类型、渲染器三样由 [component] 一并登记 —— 宿主扩展走的是
 * **完全相同**的路径，没有任何特权：覆盖 `"Text"` 的注册就能换掉内置节点。
 *
 * `Text` / `Button` / `TextField` / `Select` 不接受子节点；`Column` / `Row` / `Box` 接受任意子节点。
 * `If` / `Foreach` 不在其中 —— 它们是引擎结构节点，`EngineRegistry` 一开始就装好了。
 */
val DefaultComponents: ComponentModule = ComponentModule { registry ->
    registry
        .component("Text", create = ::TextNode, render = ::Text)
        .component("Button", create = ::ButtonNode, render = ::Button)
        .component("TextField", create = ::TextFieldNode, render = ::TextField)
        .component("Select", create = ::SelectNode, render = ::Select)
        .component("Column", ChildRule.Any, create = ::ColumnNode, render = ::Column)
        .component("Row", ChildRule.Any, create = ::RowNode, render = ::Row)
        .component("Box", ChildRule.Any, create = ::BoxNode, render = ::Box)
}
