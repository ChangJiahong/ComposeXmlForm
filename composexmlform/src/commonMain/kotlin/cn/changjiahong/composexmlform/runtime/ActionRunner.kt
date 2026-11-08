package cn.changjiahong.composexmlform.runtime

import cn.changjiahong.composexmlform.api.ActionScope
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.core.Severity
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.core.toDiagnostic
import cn.changjiahong.composexmlform.binding.ActionRef
import cn.changjiahong.composexmlform.node.AttrSite

/**
 * 执行一个动作，**任何异常都不外冒**。
 *
 * 为什么单独抽成函数：`fire()` 是 `@Composable`，测不了；而「handler 抛错时界面不能崩、
 * 错误要进上报通道」是这条链路唯一有风险的语义。抽出来后可以用普通单测覆盖
 * （见 `ActionTest`），`fire()` 只剩「拿到 scope 和 reporter，launch 一下」。
 *
 * 两类失败都以 [Code.ActionFailed] / [Code.UnregisteredAction] 上报，不带异常抛出：
 * `onClick` 里抛出的异常会直接冒到 Compose，整页都会受影响。
 */
suspend fun runAction(
    registry: ActionRegistry,
    ref: ActionRef,
    site: AttrSite,
    scope: ActionScope,
    report: (Diagnostic) -> Unit,
) {
    val handler = try {
        registry.handler(ref.name, site)
    } catch (e: XmlUiException) {
        report(e.toDiagnostic(Severity.Error))
        return
    }

    runCatching { handler.run(scope) }.onFailure { error ->
        report(
            Diagnostic(
                severity = Severity.Error,
                code = Code.ActionFailed,
                message = "动作 ${ref.name}() 执行失败：${error.message ?: error::class.simpleName}",
                tag = site.tag.takeIf { it.isNotEmpty() },
                attribute = site.attribute.takeIf { it.isNotEmpty() },
                line = site.line,
            ),
        )
    }
}
