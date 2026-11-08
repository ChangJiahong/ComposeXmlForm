package cn.changjiahong.composexmlform.core

/**
 * 诊断级别。只有两级：**能继续渲染**就是 Warning，**不能**就是 Error。
 *
 * 不做 `Info`：引擎没有「仅提示」的场景，加了只会让调用方多写一个永远不成立的分支。
 */
enum class Severity {
    Warning,
    Error,
}

/**
 * 诊断码。宿主用它做上报聚合与 i18n，不用去解析 [Diagnostic.message]。
 *
 * 这是**开放枚举**：R9 / R10 各追加自己的码，[Diagnostic] 的结构不再变。
 */
enum class Code {

    /** 标签没有注册（`<Textt>`）。Tolerant 下变 Warning 并渲染占位。 */
    UnknownTag,

    /** 节点没有读这个属性（`fontSzie`）。Tolerant 下变 Warning 并忽略。 */
    UnknownAttribute,

    /** 属性值的类型转换失败（`maxLines="三"`）。 */
    TypeMismatch,

    /** 结构错误：叶子带子节点、`<Then>` 不在 `<If>` 里、缺少必需属性等。 */
    Structure,

    /** 引用了未注册的动作。 */
    UnregisteredAction,

    /** `{{config.x}}` 里没有 `x`。 */
    UnknownConfig,

    /** 布局要求的引擎版本高于当前引擎（`minEngine`）。 */
    LayoutTooNew,

    /** 有节点类型但没有渲染器（R10）。 */
    MissingRenderer,

    /** 属性值语法错误，解析期无法判定（颜色 / 字重 / 对齐，见待办）。 */
    UnknownValue,

    /** 动作执行抛异常（R9）。 */
    ActionFailed,
}

/**
 * 一条诊断。
 *
 * 坐标四要素与 [XmlUiException] 一致（标签 / 属性 / 行号），因为两者描述的是同一类错误：
 * 区别只是「收集起来」还是「当场抛」。
 */
class Diagnostic(
    val severity: Severity,
    val code: Code,
    val message: String,
    val tag: String? = null,
    val attribute: String? = null,
    val line: Int = 0,
) {

    /** `第 3 行 <Text> 属性 'maxLines' — ...`，与异常的 message 前缀同构。 */
    fun format(): String = buildString {
        if (line > 0) append("第 ").append(line).append(" 行: ")
        if (tag != null) append('<').append(tag).append('>')
        if (attribute != null) append(" 属性 '").append(attribute).append('\'')
        if (isNotEmpty()) append(" — ")
        append(message)
        append(" [").append(severity).append('/').append(code).append(']')
    }

    override fun toString(): String = format()
}

/** 把异常降级成诊断：解析期用它把「抛」改成「收集」。 */
fun XmlUiException.toDiagnostic(severity: Severity): Diagnostic =
    Diagnostic(severity, code, reason, tag, attribute, line)
