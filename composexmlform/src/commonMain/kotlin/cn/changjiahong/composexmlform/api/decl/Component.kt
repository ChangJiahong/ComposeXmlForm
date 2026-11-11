package cn.changjiahong.composexmlform.api.decl

import cn.changjiahong.composexmlform.engine.eval.EvalContext
import cn.changjiahong.composexmlform.engine.node.ContainerNode
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.render.Bindable
import cn.changjiahong.composexmlform.engine.render.DiagnosticSink

/**
 * 带属性委托的叶子节点。**没有子节点语义**，与 [Node] 一样。
 *
 * 写一个组件只需要两样：这个类（属性一行一个）+ 一个普通 Composable。
 * 属性名、类型、默认值、可写性全部在构造函数里就地声明，没有第二处清单。
 *
 * ```kotlin
 * class RatingBarNode(props: Props) : PropsNode(props) {
 *     var value by props.varLong("value", 0L)
 *     val max by props.long("max", 5L)
 *     val style = props.style()
 * }
 *
 * @Composable fun RatingBar(node: RatingBarNode) { … }
 *
 * registry.component("RatingBar", create = ::RatingBarNode, render = ::RatingBar)
 * ```
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

/** 声明期原型：只用来收集属性表，不参与渲染。 */
internal fun prototypeProps(schema: Schema): Props = Props(
    id = "",
    tag = schema.tag,
    schema = schema,
    slots = null,
    ctx = null,
    sink = DiagnosticSink.None,
    children = emptyList(),
    create = null,
)
