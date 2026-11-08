package cn.changjiahong.composexmlform.validation

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.node.FieldRules
import cn.changjiahong.composexmlform.node.ValidationPlan
import cn.changjiahong.composexmlform.store.ObservableData

/**
 * 表单校验状态：**`errors` 是独立命名空间，不进 `data`**。
 *
 * 为什么不能放进 `data`：
 * 1. `data.snapshot()` 是提交内容，错误混进去会被一起发到服务器；
 * 2. 错误是**派生状态**（由 data + 规则算出），不是用户输入 —— 放进 data 会和真实业务字段抢 key；
 * 3. `changes()` 是业务变更流，错误变化混进去会干扰宿主的脏检查。
 *
 * 只做**同步**校验。异步规则（手机号是否已注册）要 suspend + 作用域 + 取消，
 * 属于规则引擎（见待办），不在这里硬凑。
 */
class FormState(private val data: BindableData) {

    private val state = mutableStateOf<Map<String, String?>>(emptyMap())

    /** 字段路径 → 错误文案（无错则没有这个 key）。读它会订阅，可用于重组。 */
    val errors: State<Map<String, String?>> get() = state

    private var plan: ValidationPlan = ValidationPlan.Empty

    /** 由 `DynamicLayout` 在运行时构造后调用：解析期收集的规则在这里生效。 */
    fun attach(plan: ValidationPlan) {
        this.plan = plan
        // 换了布局就换了规则：旧布局留下的错误不能挂在已经不存在的字段上
        val kept = plan.rules.map { it.path.raw }.toSet()
        state.value = state.value.filterKeys { it in kept }
    }

    /** 同步校验全部字段；返回是否全部通过。 */
    fun validate(): Boolean {
        val next = LinkedHashMap<String, String?>()
        plan.rules.forEach { rule ->
            check(rule)?.let { message -> next[rule.path.raw] = message }
        }
        state.value = next
        return next.isEmpty()
    }

    /** 清掉某个字段的错误（用户开始编辑时调用，避免「一打字就红」）。 */
    fun clear(path: String) {
        if (path !in state.value) return
        state.value = state.value - path
    }

    /** 单个字段的错误文案；没有规则或没校验过都返回 null。 */
    fun errorOf(path: String): String? = state.value[path]

    private fun check(rule: FieldRules): String? {
        val text = ObservableData.asString(data.get(rule.path.raw)).trim()

        if (rule.required && text.isEmpty()) return "${rule.display} 不能为空"
        // 非必填且为空：其余规则不再适用（否则「不填就报长度不够」）
        if (text.isEmpty()) return null

        rule.minLength?.let { min ->
            if (text.length < min) return "${rule.display} 至少 $min 个字符"
        }
        rule.maxLength?.let { max ->
            if (text.length > max) return "${rule.display} 最多 $max 个字符"
        }
        rule.pattern?.let { pattern ->
            if (!Regex(pattern).matches(text)) return "${rule.display} 格式不正确"
        }
        return null
    }
}
