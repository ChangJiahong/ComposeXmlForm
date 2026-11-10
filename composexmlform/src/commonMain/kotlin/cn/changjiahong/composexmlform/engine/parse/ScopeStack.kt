package cn.changjiahong.composexmlform.engine.parse

import cn.changjiahong.composexmlform.engine.env.EnvSchema

/**
 * 解析期的局部名字栈（阶段 ②）。
 *
 * 它回答的问题只有一个：`{{row.name}}` 里的 `row` 是**局部变量**还是 `data` 的字段？
 *
 * ```
 * <Foreach source="{{data.list}}" item="row">
 *     <Text text="{{row.name}}"/>     ← 解析 children 时 row 在栈里
 * </Foreach>
 * <Text text="{{row.name}}"/>         ← 出了 Foreach，row 不在栈里 → 兜底成 data.row
 * ```
 *
 * **必须先收集再解析子节点**：结构属性（`item="row"`）是本元素自己去读的，
 * 而用到 `row` 的是它的 children。顺序反了，`{{row.name}}` 会被判成 `data.row`，
 * 运行期永远读不到值，还可能命中同名的意外字段。
 *
 * 与运行期的 `Env` 是两回事：这里只有**名字**，没有值，作用域按语法嵌套压栈弹栈；
 * `Env` 那条链是渲染时真正建立、`Foreach` 每帧原地更新的。解析期的栈**不参与求值**。
 */
internal class ScopeStack(private val schema: EnvSchema) {

    private val frames = ArrayList<Set<String>>(4)

    val depth: Int get() = frames.size

    fun push(names: List<String>) {
        frames += names.toSet()
    }

    fun pop() {
        if (frames.isNotEmpty()) frames.removeAt(frames.lastIndex)
    }

    fun isLocal(name: String): Boolean = frames.any { name in it }

    /** 当前可见的全部局部名字，用于错误消息。 */
    fun locals(): Set<String> = frames.flatten().toSet()

    fun isGlobal(name: String): Boolean = schema.isReserved(name)

    /** 全局对象名里与 [name] 编辑距离足够近的那个 —— 拼错时给建议。 */
    fun nearestGlobal(name: String): String? = nearest(name, schema.reserved)
}

/** Levenshtein 距离，只给诊断用。属性名、变量名都短，全矩阵足够。 */
internal fun nearest(name: String, candidates: Set<String>): String? {
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    for (candidate in candidates) {
        val distance = editDistance(name, candidate)
        if (distance < bestDistance) {
            bestDistance = distance
            best = candidate
        }
    }
    return if (best != null && bestDistance <= 2) best else null
}

internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)

    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
