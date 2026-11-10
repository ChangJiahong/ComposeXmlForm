package cn.changjiahong.composexmlform.engine.value

/**
 * 一个**位置**的描述：`root` + 若干段。段表**包含首段**，所以 `segments[0] == root`（绝对路径时）。
 *
 * | 形态 | 例 | 语义 |
 * | --- | --- | --- |
 * | 绝对 | `Path("data", ["data","user","name"])` | 首段是全局对象名，直接命中 globals，跳过作用域链 |
 * | 相对 | `Path(null, ["user","name"])` | 沿作用域链查找，未命中再按槽位类型兜底（V-13） |
 *
 * `root` 不是「第一段」的冗余副本，而是**解析期判定的结果**：首段是否在 `EnvSchema` 的全局对象名集合里。
 * 运行期不做字符串前缀判断 —— 这既是语义要求，也让绝对路径成为快路径（跳过链查找）。
 *
 * 数字段（`[0]`）与普通段不区分：`DataArray` 会把 `"0"` 当索引、`DataObject` 会当 key，
 * 由容器类型唯一决定，不需要额外标记。
 */
data class Path(
    val root: String?,
    val segments: List<String>,
) {

    val isAbsolute: Boolean get() = root != null

    /** 语法上的首段。绝对路径时就是全局对象名。 */
    val head: String? get() = segments.firstOrNull()

    /** 首段之后的部分 —— 查找 / 下降时真正要走的段。 */
    val rest: List<String> get() = segments.drop(1)

    fun child(name: String): Path = copy(segments = segments + name)

    fun withSegments(extra: List<String>): Path = copy(segments = segments + extra)

    /**
     * 解析期确认首段是全局对象后调用。段表不变，只打标记。
     *
     * 前提：`segments.first() == global`。若首段不是它，说明调用方判错了，直接抛而不是静默纠正。
     */
    fun withRoot(global: String): Path {
        require(segments.firstOrNull() == global) { "首段『${head}』与根『$global』不一致" }
        return copy(root = global)
    }

    /** 只有相对路径能被兜底成某个根；已有根的不动。 */
    fun fallbackTo(global: String): Path =
        if (root != null) this else Path(global, listOf(global) + segments)

    /**
     * 换掉前 [count] 段（引用写入时用 origin 的前缀 + 本次的剩余段）。
     *
     * `u.name = x` 且 `u` 的 origin 是 `data.user` → `data.user` + `["name"]`。
     */
    fun replacePrefix(count: Int, with: Path): Path =
        Path(with.root, with.segments + segments.drop(count))

    /** 人读形式：`data.user.name`。诊断与调试用，不参与查找。 */
    fun display(): String = segments.joinToString(".")

    override fun toString(): String = display()

    companion object {

        val Empty = Path(null, emptyList())

        fun relative(vararg segments: String): Path = Path(null, segments.toList())

        fun absolute(root: String, vararg rest: String): Path = Path(root, listOf(root) + rest)

        /** 由已切分好的段构造相对路径。 */
        fun ofSegments(segments: List<String>): Path = Path(null, segments)

        /**
         * 解析绑定语法里的路径。返回的 `root` 恒为 `null` —— 是不是绝对路径要等解析器查 schema，
         * 这里只负责字符串层面的合法性。
         *
         * 返回 `null` 表示不是合法路径（空串、双点、非法起始字符、非数字下标）。
         */
        fun parse(raw: String): Path? {
            val segments = ParsePath.segmentsOf(raw)
            return if (segments.isEmpty()) null else Path(null, segments)
        }
    }
}

/**
 * 路径切分。`a.b[0].c` → `["a","b","0","c"]`。
 *
 * 只接受标识符与**整数字下标**：绑定路径里出现运算符或变量下标就没有意义了（那该写成 `{}`）。
 *
 * 状态机而不是 `split('.')`：`a[0].b` 的 `]` 后面紧跟 `.`，用字符串切分会切出一个空段。
 */
internal object ParsePath {

    fun segmentsOf(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        val out = ArrayList<String>(4)
        var i = 0

        // true = 期待一个新段；false = 期待分隔符（`.` 或 `[`）
        var expectSegment = true

        while (i < text.length) {
            val c = text[i]
            if (expectSegment) {
                when {
                    c == '[' -> {
                        val close = text.indexOf(']', i + 1)
                        if (close < 0) return emptyList()
                        val inside = text.substring(i + 1, close).trim()
                        if (inside.isEmpty() || !inside.all { it.isDigit() }) return emptyList()
                        out += inside
                        i = close + 1
                        expectSegment = false
                    }

                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                        out += text.substring(start, i)
                        expectSegment = false
                    }

                    else -> return emptyList()
                }
            } else {
                when (c) {
                    '.' -> {
                        i++
                        expectSegment = true
                    }

                    '[' -> {
                        val close = text.indexOf(']', i + 1)
                        if (close < 0) return emptyList()
                        val inside = text.substring(i + 1, close).trim()
                        if (inside.isEmpty() || !inside.all { it.isDigit() }) return emptyList()
                        out += inside
                        i = close + 1
                    }

                    else -> return emptyList()
                }
            }
        }

        // 以 `.` 结尾（`a.`）时还停在「期待新段」，非法
        return if (expectSegment) emptyList() else out
    }
}
