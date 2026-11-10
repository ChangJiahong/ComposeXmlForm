package cn.changjiahong.composexmlform.engine.diag

/**
 * 引擎异常基类。所有带源码位置的异常都从这里派生，方便宿主统一捕获。
 *
 * 为什么不各自继承 `RuntimeException`：宿主需要「一条 catch 拿到全部引擎错误并取出 [span]」，
 * 分散继承就得多写 N 个 catch 分支。
 */
open class EngineException(
    message: String,
    val span: Span? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** 转成诊断，供「收集而非抛出」的调用点使用（解析期）。 */
    open fun toDiagnostic(severity: Severity = Severity.Error, code: Code): Diagnostic =
        Diagnostic(severity, code, message ?: "", span)
}

/** 词法 / 语法错误。只在解析期出现，运行期不应抛出。 */
class SyntaxError(
    message: String,
    span: Span? = null,
) : EngineException(message, span)

/**
 * 求值期错误：类型不匹配、`Absent` 参与运算、在属性上下文调用挂起函数…
 *
 * [code] 默认 `ScriptRuntime`；「需要挂起上下文」这类也是它，只是消息不同。
 */
class EvalError(
    message: String,
    span: Span? = null,
    val code: Code = Code.ScriptRuntime,
) : EngineException(message, span)

/**
 * 写只读容器。
 *
 * 单独成型而不是复用 `EvalError`，是因为它的处置不同：脚本里是错误，宿主里是编程错误
 * （`config` 被写说明代码有 bug，不是数据有问题）。
 */
class FrozenWriteException(
    val key: String,
) : EngineException("『$key』属于只读容器，不可写入")
