package cn.changjiahong.composexmlform.engine.node

import cn.changjiahong.composexmlform.engine.value.AnyAdapter
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * 引擎内置的**结构节点**：`If` / `Then` / `Else` / `Foreach`。
 *
 * 它们不是 UI 组件 —— 没有尺寸、没有绘制、不产生任何像素。它们的职责只有两件：
 * **决定渲染哪些子节点**（`If`）与**用哪一套局部变量渲染**（`Foreach`）。
 *
 * 为什么放进引擎而不是留给宿主：
 *
 * - `Foreach` 的行 env 稳定性（R-2）是全系统最难写对的一处 —— 每个宿主各写一遍，
 *   等于把同一个坑复制 N 份；
 * - `If` 的 `ChildRule.Only(Then / Else)` 与分支语义必须与解析层的子节点校验对齐，
 *   放在宿主侧就要把 `ChildRule` 的能力重复实现一次。
 *
 * 它们仍然遵守 `node` 包的边界：只持有 [AttrSlot]，不含 env、不含值、不 import `eval`。
 * 求值发生在渲染层（`engine.render`）。
 */

/** `<If>` 的 then 分支容器。存在只是为了让 `ChildRule.Only` 能指名道姓。 */
internal class ThenNode(id: String, children: List<Node>) : ContainerNode(id, children)

/** `<If>` 的 else 分支容器。 */
internal class ElseNode(id: String, children: List<Node>) : ContainerNode(id, children)

/**
 * `<If test="…">`：按布尔值选一支渲染。
 *
 * 分支不保留状态（R-6）：切走再切回来，内部 `Input` 的光标、滚动位置都会重来。
 * 这是刻意的 —— 「留在后台的分支继续响应 data 变化」在表单里是反直觉的，
 * 而且会让「界面上有 3 个同名输入框」这种事变得难以解释。
 *
 * [test] 是**必填**的：没有条件的 `If` 只能靠默认 `false` 变成「永不渲染的子树」，
 * 那是纯粹的配置错误，解析期就该报。
 */
internal class IfNode(
    id: String,
    val test: AttrSlot<Boolean>,
    val thenBranch: Node?,
    val elseBranch: Node?,
) : ContainerNode(id, listOfNotNull(thenBranch, elseBranch))

/**
 * `<Foreach source="…" item="row" index="i" key="id">`：按数组逐行渲染。
 *
 * 三个属性里只有 [source] 是数据；[item] / [index] 是**结构属性**（N-2），
 * 它们的值不是运行时算出来的，而是「本节点子树里生效的名字」，
 * 所以解析期就要收裸标识符并压进作用域栈（否则 `<Foreach>` 内部写 `{{row.name}}`
 * 会被当成 `data.row.name`）。
 *
 * [keyBy] 不是结构属性：它是「拿元素对象的哪个字段当行 key」，值本身不进作用域。
 * 行 key 决定 Compose 的身份 —— 用 `id` 而不是下标，才能在列表头部插入一条时
 * 保住后续所有行的内部状态（输入框已输入的内容、动画进度）。
 */
internal class ForeachNode(
    id: String,
    val source: AttrSlot<Value>,
    val item: AttrSlot<String>,
    val index: AttrSlot<String>,
    val keyBy: AttrSlot<String>,
    children: List<Node>,
) : ContainerNode(id, children)

/**
 * 内置结构节点的声明表。
 *
 * 宿主把它 `plus` 进自己的 [NodeTypes] 即可；`data` / `action` 之外不需要任何额外配置。
 */
internal object StructuralTypes {

    /** `<If test="{{data.ok}}">`。 */
    val Test: AttrDef<Boolean> = Attr.bool("test", required = true)

    /** `<Foreach source="{{data.list}}">`。类型在运行期判（可能是 `Absent`）。 */
    val Source: AttrDef<Value> = Attr.of("source", AnyAdapter, required = true)

    /** 行元素的注入名，默认 `item`。 */
    val Item: AttrDef<String> = Attr.string("item", default = "item", structural = true)

    /** 行下标的注入名，默认 `index`。 */
    val Index: AttrDef<String> = Attr.string("index", default = "index", structural = true)

    /** 元素对象里用作行 key 的字段名。没写就用下标。 */
    val KeyBy: AttrDef<String> = Attr.string("key")

    val If: NodeType = NodeType(
        tag = "If",
        attributes = listOf(Test),
        childRule = ChildRule.Only(setOf("Then", "Else")),
    ) { id, attrs, children ->
        IfNode(
            id = id,
            test = attrs.attr(Test),
            thenBranch = children.firstOrNull { it is ThenNode },
            elseBranch = children.firstOrNull { it is ElseNode },
        )
    }

    val Then: NodeType = NodeType("Then", childRule = ChildRule.Any) { id, _, children ->
        ThenNode(id, children)
    }

    val Else: NodeType = NodeType("Else", childRule = ChildRule.Any) { id, _, children ->
        ElseNode(id, children)
    }

    val Foreach: NodeType = NodeType(
        tag = "Foreach",
        attributes = listOf(Source, Item, Index, KeyBy),
        childRule = ChildRule.Any,
    ) { id, attrs, children ->
        ForeachNode(
            id = id,
            source = attrs.attr(Source),
            item = attrs.attr(Item),
            index = attrs.attr(Index),
            keyBy = attrs.attr(KeyBy),
            children = children,
        )
    }

    /** 四个结构节点。宿主用 `StructuralTypes.All plus 自己的类型`。 */
    val All: NodeTypes = NodeTypes.of(If, Then, Else, Foreach)
}
