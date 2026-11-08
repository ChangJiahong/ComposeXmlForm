package cn.changjiahong.composexmlform.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlform.ComposeXmlForm
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.core.Severity
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.core.toDiagnostic
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.runtime.ActionRegistry
import cn.changjiahong.composexmlform.store.StoreOwner
import kotlinx.coroutines.CancellationException

/**
 * 渲染器：一种节点对应一个渲染器，**就是一个 `@Composable (T) -> Unit`**。
 *
 * 从抽象类改成 `fun interface` 的理由：
 * - 它没有任何状态和可复写的行为，「必须继承」只带来写法负担（`class X : NodeRenderer<T>()`）；
 * - 原来的 `protected RenderChild` 与顶层 [RenderNode] 是同一件事的两个名字，
 *   拆成基类成员之后，「怎么画子节点」有了两个入口 —— 去掉成员，只留 [RenderNode]。
 *
 * 递归策略仍然在**各个容器自己**手里：`Foreach` 把同一个子节点渲染 N 次（每行不同作用域），
 * `If` 只渲染一支，`Column` 按顺序全画。引擎不替它们遍历，所以这里只需要「画一个节点」。
 *
 * 两种注册写法都成立：
 * ```
 * registry.renderers.register(TextNode::class, TextNodeRenderer())     // object / class 实例
 * registry.renderers.register(TextNode::class, NodeRenderer { ... })   // 尾 lambda（SAM）
 * ```
 */
fun interface NodeRenderer<T : Node> {

    @Composable
    fun Render(node: T)
}

/** 当前作用域。CompositionLocal 只传递 Owner 引用，不给 Map 注入响应式能力。 */
val LocalStoreOwner = staticCompositionLocalOf<StoreOwner> {
    throw XmlUiException("LocalStoreOwner 未提供：节点必须在 DynamicLayout 内渲染")
}

val LocalActionRegistry = staticCompositionLocalOf<ActionRegistry> {
    throw XmlUiException("LocalActionRegistry 未提供：节点必须在 DynamicLayout 内渲染")
}

/**
 * 当前注册表。
 *
 * 默认值是全局表而不是抛错：这样「忘了在根部供给」不会让界面挂掉，
 * 而是退回内置组件 —— 渲染器查不到某个标签时的报错已经足够清楚。
 * 想让一个子树用独立注册表，用 `ComposeXmlFormProvider(registry) { ... }`。
 */
val LocalEngineRegistry = staticCompositionLocalOf<EngineRegistry> { ComposeXmlForm.registry }

/**
 * 统一渲染入口：根节点和所有容器都用它递归。
 *
 * 它是**唯一**的分派点，所以「补注册渲染器」这种事只需在这里判一次：
 * 节点类型没有渲染器时**不抛**，改为渲染占位并上报 [Code.MissingRenderer]，
 * 界面上出现一块明显的占位框，其余部分照常渲染与交互。
 *
 * 能力边界（实测，别假设更多）：
 * - 渲染器未注册 → **能兜**，就是这里的查表判断，不需要 try；
 * - 渲染器同步抛错 → **兜不住**。Compose 编译器明确禁止
 *   「`try/catch` 包裹 composable 调用」（`Try catch is not supported around
 *   composable function invocations`），所以渲染器里抛出的错会直接冒到组合之外；
 * - measure / layout / draw 阶段、快照应用阶段 → 同样兜不住（不在组合调用栈里）。
 *
 * 因此 R10 的定位是「**缺失渲染器的局部降级 + 诊断收集**」，不是「永不崩」。
 * 渲染器内部的前置校验请自己 `runCatching` 后渲染占位；全局兜底仍需平台层
 * （Android 的 `Thread.setDefaultUncaughtExceptionHandler`、宿主自己的 error boundary）。
 *
 * 自定义占位：外层 `CompositionLocalProvider(LocalRenderFallback provides { node, d -> ... })`。
 */
@Composable
fun RenderNode(node: Node) {
    val registry = LocalEngineRegistry.current
    val fallback = LocalRenderFallback.current
    val report = LocalRenderDiagnostics.current

    // registry.version 进 key：注册表变了要重新查表，否则「补注册渲染器」之后这一格仍是占位。
    val renderer = remember(registry, registry.version, node) {
        registry.renderers.rendererOrNull(node)
    }

    if (renderer != null) {
        renderer.Render(node)
        return
    }

    val diagnostic = Diagnostic(
        severity = Severity.Error,
        code = Code.MissingRenderer,
        message = "没有为节点类型 ${node::class.simpleName} 注册渲染器",
        tag = node::class.simpleName,
        line = node.line,
    )
    SideEffect { report(diagnostic) }
    (fallback ?: { n, d -> DefaultRenderFallback(n, d) })(node, diagnostic)
}
