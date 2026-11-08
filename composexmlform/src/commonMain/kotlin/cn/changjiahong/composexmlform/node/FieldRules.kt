package cn.changjiahong.composexmlform.node

import cn.changjiahong.composexmlform.binding.ValuePath

/**
 * 一条字段级规则：来自 XML 属性（`required` / `minLength` / `maxLength` / `pattern`），
 * 解析期收集，绑定路径就是该控件的 `value="{{path}}"`。
 *
 * 为什么规则挂在**路径**上而不是节点上：校验结果要按「字段」查（`errors["user.name"]`），
 * 而字段身份本来就由绑定路径定义。同一路径被两个控件绑定时，规则以最后一条为准。
 *
 * 放在 `node` 包（而不是 `validation`）是为了避免 `node → validation → node` 的循环依赖 ——
 * [Attrs] 在解析期要往清单里追加，和 [ActionUsage] 是同一个理由。
 */
class FieldRules(
    /** 字段路径：与该控件 `value="{{path}}"` 相同。 */
    val path: ValuePath,

    /** 文案里怎么称呼这个字段：优先用控件的 `label`，否则用路径原文。 */
    val label: String? = null,

    val required: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,

    /** 正则源码；编译失败在解析期就报错，不会留到校验时。 */
    val pattern: String? = null,
) {

    /** 给人看的字段名。 */
    val display: String get() = label?.takeIf { it.isNotBlank() } ?: path.raw
}

/**
 * 一份解析结果里收集到的全部字段规则。
 *
 * 它是**解析期产物**、和节点树一起产生：规则来自 XML 属性，所以只有解析器知道
 * 「哪些字段有约束」。运行期的 `FormState` 只是执行者。
 */
class ValidationPlan(val rules: List<FieldRules> = emptyList()) {

    val isEmpty: Boolean get() = rules.isEmpty()

    companion object {
        val Empty: ValidationPlan = ValidationPlan()
    }
}
