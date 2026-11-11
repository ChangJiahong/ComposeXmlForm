package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.node.AttrRef
import cn.changjiahong.composexmlform.engine.node.AttrSlot
import cn.changjiahong.composexmlform.engine.value.CoerceException
import cn.changjiahong.composexmlform.engine.value.Value

/**
 * `AttrSlot` 的读取。
 *
 * **为什么在 `eval` 而不是 `node`**：读取要跑表达式（`{}`）、要建函数（`@{}`）、
 * 要按槽位类型兜底找全局对象 —— 三件事都需要求值器与环境。把它们搬进 `node` 包
 * 会让节点树依赖 `env`，节点就不再是「纯结构」，同一棵树也无法跨 runtime 复用。
 * 依赖方向因此固定为 `eval → node`（本包的 [AttrSlot] 扩展），`node` 不知道 `eval` 存在。
 *
 * 四条读取路径：
 *
 * | 写法 | 动作 | 订阅 |
 * | --- | --- | --- |
 * | `"raw"` | 声明期已定型，这里只收敛 | 无 |
 * | `{{path}}` | `env.read`，读到 `state` 就自动订阅 | **有** |
 * | `{expr}` | 求值器跑一遍 | 取决于表达式里读了什么 |
 * | `@{…}` | 包成 [ScriptFunction]，**不调用** | 无 |
 * | 没写 | `AttrDef.default` | 无 |
 *
 * 收敛（`Value → T`）只有一处：[cn.changjiahong.composexmlform.engine.value.TypeAdapter.coerce]。
 * 字面量不例外 —— 它先过 `type.literal` 变成 `Value`，再和别的路径走同一个收敛口。
 *
 * 返回值是 `T?`：**属性可以没有值**（`Attr.int("size")` 缺省就是 `null`）。
 * 「没写」走 `def.defaultValue()` 且**不抛** —— `required` 缺失已经在解析期报过
 * `MissingRequiredAttribute`，运行期再抛一次只会把一条可降级的配置问题升级成崩溃。
 */
internal fun <T> AttrSlot<T>.read(ctx: EvalContext): T? {
    val site = site
    val value: Value = when (val ref = this.ref) {
        AttrRef.Absent -> return def.defaultValue()

        is AttrRef.Literal -> def.type.literal(ref.raw, site)

        is AttrRef.Bind -> ctx.env.read(ref.path, def.slotKind)

        // 兜底提示由槽位类型给：函数槽位里裸写 `click()` 要落到 action.click（V-13）
        is AttrRef.Code -> Evaluator.evaluate(ref.expr, ctx.withHint(def.slotKind))

        // 只建值，不执行（N-3）；参数与环境在调用时才绑定
        is AttrRef.Lambda -> ScriptFunction(
            params = ref.expr.params,
            body = ref.expr.body,
            env = ctx.env,
            hint = def.slotKind,
            methods = ctx.methods,
            span = ref.expr.span,
        )
    }
    return def.type.coerce(value, site)
}

/**
 * 宽松版：收敛失败返回 `null` 而不是抛。
 *
 * 给渲染层做「降级为默认值 + `AttributeFallback` 诊断」用（R-4）——
 * 渲染期抛异常会整棵树炸掉，而一个颜色写错了不该让整个页面白屏。
 */
internal fun <T> AttrSlot<T>.readOrNull(ctx: EvalContext): T? =
    try {
        read(ctx)
    } catch (_: CoerceException) {
        null
    }

/**
 * 宽松版 + 落到默认值。渲染层的默认入口。
 *
 * 注意顺序：先读，读失败才用默认。反过来（缺失就返回默认）会把
 * 「写了但收敛失败」与「没写」混成同一种情况，诊断就报不出来了。
 */
internal fun <T> AttrSlot<T>.readOrDefault(ctx: EvalContext): T? =
    readOrNull(ctx) ?: def.defaultValue()
