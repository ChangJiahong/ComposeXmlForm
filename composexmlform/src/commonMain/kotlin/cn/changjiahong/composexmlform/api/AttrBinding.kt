package cn.changjiahong.composexmlform.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.render.LocalDiagnostics
import cn.changjiahong.composexmlform.engine.render.rememberEvalContext
import cn.changjiahong.composexmlform.engine.render.renderValue
import cn.changjiahong.composexmlform.engine.value.WriteSource

/**
 * 可写槽位的「当前值 + 写回」。
 *
 * 三条路径，按优先级：
 *
 * | 情况 | 读 | 写 |
 * | --- | --- | --- |
 * | `value="{{path}}"` | 绑定路径的实时值 | `env.assign(path)`，发 `UserBinding` 事件 |
 * | 没写 `value` | 声明的默认值 | 就地改一份局部 state（非受控输入框） |
 * | 写了但不是绑定（`value="abc"`） | 字面量 | 就地改局部 state |
 *
 * 第三行是「可写槽位只接受 `{{path}}`」的兜底：解析期已经报过 `LiteralInWritableSlot`，
 * 运行期不该再崩一次 —— 用户还能继续打字，只是不回写任何数据。
 *
 * 局部 state 活在 composition 里而不是节点上：同一个节点在 `Foreach` 里会被渲染成 N 行，
 * 把 `MutableState` 放进节点等于让所有行共享同一个输入框内容。
 */
@Composable
fun <T> AttrSlot<T>.rememberField(): Pair<T?, (T) -> Unit> {
    val ctx = rememberEvalContext()
    val sink = LocalDiagnostics.current
    val path = boundPath

    val initial = remember(ctx, path) { if (path == null) renderValue(ctx, sink) else null }
    val local = remember(ctx, path) { mutableStateOf(initial) }

    val current: T? = if (path != null) renderValue(ctx, sink) else local.value

    val onChange: (T) -> Unit = { next ->
        val written = path != null && ctx.env.assign(path, next, WriteSource.UserBinding)
        if (!written) local.value = next
    }
    return current to onChange
}
