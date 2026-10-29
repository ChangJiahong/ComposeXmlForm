package cn.changjiahong.composexmlui.binding

import cn.changjiahong.composexmlui.core.XmlUiException

/**
 * 绑定语法解析器。
 *
 * 语法约定（第一版刻意保持极简）：
 * - `{{path}}` 表示绑定，path 形如 `a`、`a.b.c`、`a[0].b`。
 * - 其余内容一律视为字面值。
 * - 不支持插值拼接（`prefix {{a}} suffix`）、运算、函数调用、脚本。
 */
object BindingParser {

    private const val OPEN = "{{"
    private const val CLOSE = "}}"

    /** 解析单个属性值。 */
    fun parseAttribute(raw: String): BindingExpression {
        val text = raw.trim()
        val openIndex = text.indexOf(OPEN)
        val closeIndex = text.lastIndexOf(CLOSE)

        if (openIndex < 0 && closeIndex < 0) {
            return BindingExpression.Literal(text)
        }
        if (openIndex != 0 || closeIndex != text.length - 2 || closeIndex < openIndex) {
            throw XmlUiException(
                "绑定表达式格式错误: '$raw'。绑定值必须整体写成 {{path}}，不支持与其他文本拼接",
            )
        }
        val body = text.substring(OPEN.length, text.length - CLOSE.length).trim()
        if (body.isEmpty()) {
            throw XmlUiException("绑定表达式为空: '$raw'，正确写法为 {{path}}")
        }
        if (body.contains(OPEN) || body.contains(CLOSE)) {
            throw XmlUiException("绑定表达式嵌套非法: '$raw'")
        }
        return BindingExpression.Path(parsePath(body, raw))
    }

    /**
     * 解析路径。
     * @param text 路径文本，例如 `user.name`
     * @param source 用于错误提示的原始表达式
     */
    fun parsePath(text: String, source: String): ValuePath {
        val src = text.trim()
        if (src.isEmpty()) {
            throw XmlUiException("绑定路径为空: '$source'")
        }

        var index = 0

        fun fail(message: String): Nothing =
            throw XmlUiException("绑定表达式 '$source' $message")

        fun readIdentifier(): String {
            if (index >= src.length || !(src[index].isLetter() || src[index] == '_')) {
                fail("在位置 ${index + 1} 期望一个标识符（字母或下划线开头）")
            }
            val start = index
            while (index < src.length && (src[index].isLetterOrDigit() || src[index] == '_')) {
                index++
            }
            return src.substring(start, index)
        }

        val segments = mutableListOf<PathSegment>()
        segments += PathSegment.Prop(readIdentifier())

        while (index < src.length) {
            when (src[index]) {
                '.' -> {
                    index++
                    segments += PathSegment.Prop(readIdentifier())
                }
                '[' -> {
                    index++
                    val start = index
                    while (index < src.length && src[index].isDigit()) index++
                    if (start == index) fail("的第 ${index + 1} 个字符处，方括号内必须是数字下标")
                    val number = src.substring(start, index).toIntOrNull()
                        ?: fail("的下标数值非法")
                    if (index >= src.length || src[index] != ']') fail("缺少配对的 ']'")
                    index++
                    segments += PathSegment.Index(number)
                }
                else -> fail("在位置 ${index + 1} 出现不支持的字符 '${src[index]}'，仅支持 a.b 与 a[0] 形式")
            }
        }
        return ValuePath(src, segments)
    }

    /** 解析 `action="submit()"`。 */
    fun parseAction(raw: String, tag: String? = null, line: Int = 0): ActionRef {
        val text = raw.trim()
        val openParen = text.indexOf('(')
        val closeParen = text.lastIndexOf(')')

        fun fail(reason: String): Nothing =
            throw XmlUiException(reason, tag = tag, attribute = "action", line = line)

        if (openParen <= 0 || closeParen != text.length - 1) {
            fail("action 必须是 functionName() 形式，实际为 '$raw'")
        }
        val name = text.substring(0, openParen).trim()
        val args = text.substring(openParen + 1, text.length - 1).trim()
        if (args.isNotEmpty()) {
            fail("action 不支持参数，实际为 '$raw'。业务逻辑请在注册的函数内部自行读取数据")
        }
        if (!isIdentifier(name)) {
            fail("action 的函数名 '$name' 非法，只能包含字母、数字与下划线且不能以数字开头")
        }
        return ActionRef(name, raw)
    }

    fun isIdentifier(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!(text[0].isLetter() || text[0] == '_')) return false
        return text.all { it.isLetterOrDigit() || it == '_' }
    }
}
