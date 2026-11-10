package cn.changjiahong.composexmlform.engine.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.eval.EvalContext
import cn.changjiahong.composexmlform.engine.eval.MethodExtensions
import cn.changjiahong.composexmlform.engine.node.Node

/**
 * 当前作用域。
 *
 * **env 必须从 CompositionLocal 拿，不能挂在节点上** —— 这不是风格问题，是 `Foreach`
 * 决定的：一个节点实例渲染 N 行，每行 env 不同。env 一旦能挂在节点上，
 * 第 2 行必然读到第 1 行的值。
 *
 * 传的只是 `Env` **引用**，不传数据、不注入响应式能力：响应式来自
 * `EnvStore` 里的 `MutableState`，与这条 CompositionLocal 无关。
 */
val LocalEnv: ProvidableCompositionLocal<Env> = staticCompositionLocalOf {
    error("节点必须在 DynamicLayout 内渲染：LocalEnv 没有被提供")
}

/**
 * 宿主扩展链（每 runtime 一份）。
 *
 * 默认空链而不是报错：没注册扩展方法时 `list.sum()` 应该报「不是数组的方法」，
 * 而不是让整页崩在「找不到扩展链」上。
 */
val LocalMethods: ProvidableCompositionLocal<MethodExtensions> = staticCompositionLocalOf {
    MethodExtensions.Empty
}

/** 当前渲染器注册表。 */
val LocalRenderers: ProvidableCompositionLocal<Renderers> = staticCompositionLocalOf {
    Renderers.Empty
}

/**
 * 渲染期诊断出口。
 *
 * 渲染期的诊断与解析期不同：**同一个属性可能被上报多次**（每次重组都会重新取值）。
 * 去重是 sink 实现方的责任（按 `code + tag + attribute` 做集合），引擎只保证
 * 「每次降级都报一次」。
 */
fun interface DiagnosticSink {

    fun report(diagnostic: Diagnostic)

    companion object {
        /** 什么都不做。测试与「不关心诊断」的宿主用它。 */
        val None: DiagnosticSink = DiagnosticSink { }
    }
}

/** 渲染期诊断。默认丢弃，`DynamicLayout` 会供给一个真正收集的实现。 */
val LocalDiagnostics: ProvidableCompositionLocal<DiagnosticSink> = staticCompositionLocalOf {
    DiagnosticSink.None
}

/**
 * 缺渲染器时的占位。
 *
 * 默认实现画一块带红框的提示，而不是抛错 —— 一个标签忘了注册不该让整页白屏，
 * 界面上出现一块明显的占位反而更容易定位。
 */
val LocalRenderFallback: ProvidableCompositionLocal<@Composable (Node, Diagnostic) -> Unit> =
    staticCompositionLocalOf {
        { node, diagnostic -> DefaultRenderFallback(node, diagnostic) }
    }

/**
 * 本次渲染的求值现场。
 *
 * `remember(env, methods)` 而不是每次新建：`EvalContext` 会被 `AttrSlot.read` 一路传下去，
 * 每帧新建几十个纯属浪费；同时它必须是**稳定的** —— 变更它等于变更所有下游读取的身份。
 *
 * 不缓存 `frame`：属性求值永远没有调用帧（脚本里才有），`VarDecl` 在属性上下文会报错。
 */
@Composable
fun rememberEvalContext(env: Env = LocalEnv.current): EvalContext {
    val methods = LocalMethods.current
    return remember(env, methods) { EvalContext(env, methods = methods) }
}
