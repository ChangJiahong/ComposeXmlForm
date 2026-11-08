package cn.changjiahong.composexmlform.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlform.api.ActionScope
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.binding.ActionRef
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.ValAttr
import cn.changjiahong.composexmlform.runtime.runAction
import kotlinx.coroutines.launch

/** 动作执行时要用到的业务数据门面（`ActionScope.data`）。 */
val LocalBindableData = staticCompositionLocalOf<BindableData> {
    throw XmlUiException("LocalBindableData 未提供：节点必须在 DynamicLayout 内渲染")
}

/**
 * 把动作属性变成可点击函数。渲染器唯一的出口：
 * ```
 * Button(onClick = node.action.fire()) { Text(node.text.current() ?: "") }
 * Modifier.clickable(onClick = node.onTap.fire())
 * ```
 *
 * 三点实现约束：
 * 1. 动作恒为常量（`{{path}}` 在解析期就被拒），所以直接读 `constant`，**不建立订阅**；
 * 2. `() -> Unit` 里 launch 到 `rememberCoroutineScope`，handler 的挂起不影响点击回调；
 * 3. handler 抛异常在 [runAction] 里被收住并上报，不会冒到 Compose。
 */
@Composable
fun ValAttr<ActionRef>?.fire(): () -> Unit {
    val attr = this ?: return {}
    val registry = LocalActionRegistry.current
    val data = LocalBindableData.current
    val report = LocalRenderDiagnostics.current
    val coroutineScope = rememberCoroutineScope()
    val ref = remember(attr) { attr.constant }

    if (ref == null) return {}

    val site = attr.site
    return remember(ref, registry, coroutineScope, data, report) {
        {
            coroutineScope.launch {
                runAction(registry, ref, site, ActionScope(data, ref.name), report)
            }
        }
    }
}
