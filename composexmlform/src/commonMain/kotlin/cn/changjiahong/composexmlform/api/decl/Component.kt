package cn.changjiahong.composexmlform.api.decl

import androidx.compose.runtime.Composable
import cn.changjiahong.composexmlform.api.EngineRegistry
import cn.changjiahong.composexmlform.engine.eval.EvalContext
import cn.changjiahong.composexmlform.engine.node.ChildRule
import cn.changjiahong.composexmlform.engine.node.ContainerNode
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.render.Bindable
import cn.changjiahong.composexmlform.engine.render.DiagnosticSink
import cn.changjiahong.composexmlform.engine.render.NodeRenderer
import kotlin.reflect.KClass

/**
 * 带属性委托的叶子节点。**没有子节点语义**，与 [Node] 一样。
 *
 * 写一个组件只需要两样：这个类（属性一行一个）+ 一个普通 Composable。
 * 属性名、类型、默认值、可写性全部在构造函数里就地声明，没有第二处清单。
 */
abstract class PropsNode(props: Props) : Node(props.id), Bindable {

    val props: Props = props

    override fun bind(ctx: EvalContext, sink: DiagnosticSink): Node =
        props.create?.invoke(props.rebind(ctx, sink)) ?: this
}

/** 带属性委托的容器节点。子节点来自解析期，绑定只换现场、不换子节点。 */
abstract class PropsContainer(props: Props) : ContainerNode(props.id, props.children), Bindable {

    val props: Props = props

    override fun bind(ctx: EvalContext, sink: DiagnosticSink): Node =
        props.create?.invoke(props.rebind(ctx, sink)) ?: this
}

/**
 * 注册一个组件：**属性、构造、画法一次说完**。
 *
 * ```kotlin
 * registry.component("Stars", create = ::StarsNode, render = ::Stars)
 * ```
 *
 * 三件事由框架补齐，宿主都不写：
 *
 * 1. **属性表**：先用空 [Props] 把节点构造一次，构造期调用的每个 `props.xxx(...)` 都记进声明表
 *    （`Attr` 对象、`create` 里逐个 `attrs.attr(...)`、构造函数参数三处重复从此消失）；
 * 2. **节点类型**：`tag` / 属性表 / 子节点规则，一次性登记；
 * 3. **渲染器**：`render` 就是个 Composable，不用实现 `NodeRenderer`、不用起类名。
 *
 * [render] 收到的是**已绑定本行现场**的节点，所以可以直接 `node.value` / `node.value = x`。
 */
fun <T : Node> EngineRegistry.component(
    tag: String,
    childRule: ChildRule = ChildRule.None,
    create: (Props) -> T,
    render: @Composable (T) -> Unit,
): EngineRegistry {
    val schema = Schema(tag)
    val prototype = create(prototypeProps(schema))

    @Suppress("UNCHECKED_CAST")
    val nodeClass = prototype::class as KClass<T>
    return register(
        nodeClass = nodeClass,
        tag = tag,
        attributes = schema.decls,
        childRule = childRule,
        create = { id, attrs, children -> create(propsOf(schema, id, attrs, children, create)) },
        renderer = NodeRenderer { node -> render(node) },
    )
}

/** 声明期原型：只用来收集属性表，不参与渲染。 */
private fun prototypeProps(schema: Schema): Props = Props(
    id = "",
    tag = schema.tag,
    schema = schema,
    slots = null,
    ctx = null,
    sink = DiagnosticSink.None,
    children = emptyList(),
    create = null,
)
