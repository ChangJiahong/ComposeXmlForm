package cn.changjiahong.composexmlform.engine.diag

/** 诊断级别。`Error` 阻断（不产出节点 / 不求值），`Warning` 降级继续，`Info` 仅提示。 */
enum class Severity {
    Error,
    Warning,
    Info,
}

/**
 * 诊断码。
 *
 * 宿主用它做上报聚合与 i18n，**不要去解析 [Diagnostic.message]**。
 * 分组的顺序就是解析阶段顺序，便于按阶段过滤。
 */
enum class Code {

    // ── ① 结构解析（唯一 fail-fast 的阶段）────────────────────────
    /** XML 本身不合法：未闭合、标签不匹配、多个根。 */
    XmlMalformed,

    /** 标签没注册。 */
    UnknownTag,

    /** 同一个标签里出现两次同名属性。 */
    DuplicateAttribute,

    // ── ② 属性 ────────────────────────────────────────────────
    /** 属性没在 `AttrDef` 里声明（拼错）。 */
    UnknownAttribute,

    /** `required` 属性缺失。 */
    MissingRequiredAttribute,

    /** 属性值无法收敛到声明的类型。 */
    InvalidAttributeValue,

    // ── ③ 括号 ────────────────────────────────────────────────
    /** `{}` / `{{}}` / `@{}` 未闭合或多余。 */
    MalformedBraces,

    /** `{{}}` 里空无一物。 */
    EmptyBinding,

    /** 绑定内容不是合法路径。 */
    InvalidPath,

    // ── ④ 可写性 ──────────────────────────────────────────────
    /** `VarAttr` 槽位收到了不可写来源（`{expr}` / `@{…}` / 字面量 / 只读对象）。 */
    ReadOnlySlot,

    /** 目标全局对象没有写能力（`{{config.x}}`）。 */
    NotWritableNamespace,

    /** 可写槽位写了字面量（`value="hello"` 没有回写目标）。 */
    LiteralInWritableSlot,

    // ── ⑤ 作用域 ──────────────────────────────────────────────
    /** 首段不是已注册的全局对象名（`{{dta.x}}`）。 */
    UnknownGlobal,

    /** 首段不是作用域内的局部变量。 */
    UnknownLocal,

    /** 运行期读到不存在的路径（开发期严格模式，D-5）。 */
    UnresolvedPath,

    // ── ⑥ 脚本 ────────────────────────────────────────────────
    /** 词法 / 语法错误，带行列与源码片段。 */
    ScriptSyntax,

    /** 求值期错误：类型不匹配、`Absent` 参与运算、在属性上下文调挂起函数… */
    ScriptRuntime,

    // ── ⑦ 子节点 ──────────────────────────────────────────────
    /** `ChildRule.None` 的节点收到了子节点。 */
    ChildNotAllowed,

    /** `ChildRule.AtLeastOne` 的节点没有子节点。 */
    ChildRequired,

    /** `ChildRule.Only` 不允许该标签。 */
    ChildTagMismatch,

    // ── ⑧ 渲染 ────────────────────────────────────────────────
    /** 有节点类型但没有对应渲染器。 */
    MissingRenderer,

    /** 属性求值失败，已降级为默认值（R-4）。 */
    AttributeFallback,
}
