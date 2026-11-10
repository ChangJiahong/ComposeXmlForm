package cn.changjiahong.composexmlform.engine.render

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.env.Env
import cn.changjiahong.composexmlform.engine.node.ContainerNode
import cn.changjiahong.composexmlform.engine.node.Node
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * **唯一的分派点**：把「节点 + 当前 env」变成 Compose UI。
 *
 * 渲染器只做一件事，其余四件事它结构上就做不到（第 13 轮）：
 *
 * | 它不做什么 | 为什么做不到 |
 * | --- | --- |
 * | 不持有数据 | 节点里只有 `AttrRef`（路径 / AST），没有值 |
 * | 不写数据 | 写入口只有 `env.assign`，渲染器最多通过可写槽位的回调间接调它 |
 * | 不管理订阅 | 读 `MutableState` 即订阅，没有显式订阅 API |
 * | 不管生命周期 | env 由容器建、由 Compose 丢弃 |
 *
 * 于是「渲染器是纯函数」这句话第一次是真的：`(Node, Env) -> Unit`，无状态、可重入、可测。
 *
 * [env] 显式参数是为了 `Foreach`：它要给同一个子节点传**不同的** env。传进来的 env
 * 与当前 CompositionLocal 相同时不额外包一层 Provider，省掉每层的开销。
 *
 * 能力边界（别假设更多）：**缺渲染器能兜**（就是这里的分支）；**渲染器内部同步抛错兜不住** ——
 * Compose 编译器明确禁止 `try/catch` 包裹 composable 调用。所以这里的降级是
 * 「缺失渲染器的局部降级 + 诊断」，不是「永不崩」。
 */
@Composable
fun RenderNode(node: Node, env: Env = LocalEnv.current) {
    val renderer = LocalRenderers.current.rendererFor(node)

    if (renderer == null) {
        val diagnostic = Diagnostic(
            severity = Severity.Error,
            code = Code.MissingRenderer,
            message = "没有为节点类型 ${node::class.simpleName} 注册渲染器",
            tag = node::class.simpleName,
        )
        val sink = LocalDiagnostics.current
        // SideEffect：组合可能因重组重复执行，诊断上报是副作用，放到应用之后
        SideEffect { sink.report(diagnostic) }
        val fallback = LocalRenderFallback.current
        fallback(node, diagnostic)
        return
    }

    if (env === LocalEnv.current) {
        renderer.Render(node)
    } else {
        CompositionLocalProvider(LocalEnv provides env) {
            renderer.Render(node)
        }
    }
}

/**
 * 按顺序渲染全部子节点。
 *
 * `key(child.id)` 用**结构化路径**（`0.1.2`）而不是下标：下标在 `If` 切分支时是稳定的
 * 但语义错乱（`Else` 在位置 0 时会复用 `Then` 的状态），而结构化 id 与树位置一一对应。
 *
 * 引擎不替容器决定遍历顺序 —— 这个函数只服务「全画」这一种语义，
 * `If` / `Foreach` 有自己的循环，不会调它。
 */
@Composable
fun RenderChildren(node: ContainerNode, env: Env = LocalEnv.current) {
    for (child in node.children) {
        key(child.id) { RenderNode(child, env) }
    }
}

/**
 * 在**新的一层 env** 下渲染内容。
 *
 * 只有「引入名字」的结构才需要它（`Foreach`、未来的组件边界）。`Column` / `Row` /
 * `Box` / `If` 一律不要用 —— 它们不引入任何名字，多一层只是多一次 map 查找。
 *
 * [vars] 的**键集合**决定 env 层的身份：键变了就重建一层，值变了走响应式槽位
 * （`define` 对已有名字是原地改值），所以子节点的内部状态不会因为值变化被丢掉。
 *
 * 名字撞上全局对象名（`data` / `config` / `action` / 宿主注册的）会抛 ——
 * 那是宿主写错了，跟传错参数同级，不该在运行期被静默纠正。
 */
@Composable
fun WithEnv(vars: Map<String, Value>, content: @Composable () -> Unit) {
    val parent = LocalEnv.current
    val names = vars.keys.sorted()
    val child = remember(parent, names) { parent.child() }

    for ((name, value) in vars) child.define(name, value)

    CompositionLocalProvider(LocalEnv provides child) {
        content()
    }
}

/** 缺渲染器时的默认占位：一块红框提示，不抛、不白屏。 */
@Composable
fun DefaultRenderFallback(node: Node, diagnostic: Diagnostic) {
    Box(Modifier.border(1.dp, Color.Red).padding(4.dp)) {
        BasicText("未注册渲染器 <${node::class.simpleName}>")
    }
}
