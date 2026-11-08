package cn.changjiahong.composexmlform.parser

import cn.changjiahong.composexmlform.core.XmlUiException

/**
 * XML 元素（解析器的中间产物，尚未变成 Node）。
 */
class XmlElement(
    val tag: String,
    val attributes: Map<String, String>,
    val children: List<XmlElement>,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "<$tag line=$line>"
}

/**
 * 严格模式 XML 解析器。
 *
 * 为什么不用 HTML 解析器：Ksoup / Jsoup 面向容错 HTML，遇到未闭合标签会自动修复，
 * 无法给出「第 N 行 <Column> 未闭合」这类精确诊断，而本引擎需要把错误如实反馈给使用者。
 * 因此这里实现一个只覆盖「声明 + 注释 + 元素 + 属性」的小型严格解析器（无第三方依赖，
 * 天然支持 KMP 全平台）。若后续需要容错解析，可在 [NodeFactory] 前替换本实现。
 *
 * 明确不支持：DOCTYPE、CDATA、处理指令（除 XML 声明）、命名空间。
 */
object XmlParser {

    fun parse(xml: String): XmlElement {
        val scanner = Scanner(xml)
        scanner.skipBom()
        scanner.skipWhitespace()
        if (scanner.eof) scanner.fail("XML 内容为空，缺少根元素")

        var root: XmlElement? = null
        while (!scanner.eof) {
            when {
                scanner.startsWith("<?") -> scanner.skipUntil("?>", "XML 声明未闭合")
                scanner.startsWith("<!--") -> scanner.skipComment()
                scanner.startsWith("<!") -> scanner.fail("不支持 DOCTYPE 等声明")
                scanner.startsWith("<") -> {
                    if (root != null) {
                        scanner.fail("XML 只允许一个根元素，检测到第二个根元素 <${scanner.peekTagName()}>")
                    }
                    root = parseElement(scanner)
                }
                else -> scanner.fail("根元素之外存在非法内容 '${scanner.peek()}'")
            }
            scanner.skipWhitespace()
        }
        return root ?: scanner.fail("XML 内容为空，缺少根元素")
    }

    private fun parseElement(scanner: Scanner): XmlElement {
        val line = scanner.line
        val column = scanner.column
        scanner.expect('<', "期望元素起始符 '<'")
        if (scanner.peek() == '/') scanner.fail("出现意外的结束标签")
        if (scanner.peek() == '!') scanner.fail("不支持声明或 CDATA 段")

        val tag = scanner.readName()
        val attributes = LinkedHashMap<String, String>()

        while (true) {
            val hasWhitespace = scanner.skipWhitespace()
            if (scanner.eof) scanner.fail("标签 <$tag> 未闭合", tag)
            when {
                scanner.startsWith("/>") -> {
                    scanner.skip(2)
                    return XmlElement(tag, attributes, emptyList(), line, column)
                }
                scanner.peek() == '>' -> {
                    scanner.skip(1)
                    break
                }
                else -> {
                    if (!hasWhitespace) {
                        scanner.fail("标签 <$tag> 的属性之间缺少空格", tag)
                    }
                    val name = scanner.readName()
                    scanner.skipWhitespace()
                    scanner.expect('=', "属性 '$name' 缺少 '=' 与取值", tag)
                    scanner.skipWhitespace()
                    val quote = scanner.peek()
                    if (quote == null || (quote != '"' && quote != '\'')) {
                        scanner.fail("属性 '$name' 的值必须用双引号或单引号包裹", tag)
                    }
                    scanner.skip(1)
                    val value = scanner.readAttributeValue(quote, tag, name)
                    if (attributes.containsKey(name)) {
                        scanner.fail("标签 <$tag> 出现重复属性 '$name'", tag)
                    }
                    attributes[name] = value
                }
            }
        }

        val children = mutableListOf<XmlElement>()
        while (true) {
            if (scanner.eof) scanner.fail("标签 <$tag> 缺少结束标签 </$tag>", tag)
            when {
                scanner.startsWith("</") -> {
                    scanner.skip(2)
                    val closeTag = scanner.readName()
                    scanner.skipWhitespace()
                    scanner.expect('>', "结束标签 </$closeTag> 格式错误", tag)
                    if (closeTag != tag) {
                        scanner.fail("结束标签 </$closeTag> 与开始标签 <$tag> 不匹配", tag)
                    }
                    return XmlElement(tag, attributes, children, line, column)
                }
                scanner.startsWith("<!--") -> scanner.skipComment()
                scanner.startsWith("<![CDATA[") -> scanner.fail("不支持 CDATA 段", tag)
                scanner.peek() == '<' -> children += parseElement(scanner)
                else -> {
                    val textLine = scanner.line
                    val text = StringBuilder()
                    while (!scanner.eof && scanner.peek() != '<') {
                        text.append(scanner.advanceChar())
                    }
                    if (text.isNotBlank()) {
                        throw XmlUiException(
                            "不支持元素文本内容，界面请用属性描述（例如 <Text value=\"...\"/>），" +
                                "实际文本: \"${text.toString().trim().take(30)}\"",
                            tag = tag,
                            line = textLine,
                        )
                    }
                }
            }
        }
    }

    private class Scanner(private val src: String) {

        var pos: Int = 0
            private set
        var line: Int = 1
            private set
        var column: Int = 1
            private set

        val eof: Boolean get() = pos >= src.length

        fun peek(offset: Int = 0): Char? {
            val index = pos + offset
            return if (index < src.length) src[index] else null
        }

        fun startsWith(text: String): Boolean =
            pos + text.length <= src.length && src.regionMatches(pos, text, 0, text.length)

        fun advanceChar(): Char {
            val c = src[pos]
            pos++
            if (c == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            return c
        }

        fun skip(count: Int) {
            repeat(count) {
                if (pos < src.length) advanceChar()
            }
        }

        fun skipBom() {
            if (!eof && src[pos] == '\uFEFF') advanceChar()
        }

        fun skipWhitespace(): Boolean {
            var moved = false
            while (!eof && src[pos].isWhitespace()) {
                advanceChar()
                moved = true
            }
            return moved
        }

        fun skipUntil(marker: String, errorMessage: String) {
            val found = src.indexOf(marker, pos)
            if (found < 0) fail(errorMessage)
            while (pos < found + marker.length) advanceChar()
        }

        fun skipComment() {
            skip(4) // <!--
            val end = src.indexOf("-->", pos)
            if (end < 0) fail("注释未闭合（缺少 -->）")
            while (pos < end + 3) advanceChar()
        }

        fun peekTagName(): String {
            val start = pos + 1
            if (start >= src.length) return ""
            var index = start
            while (index < src.length && isNameChar(src[index])) index++
            return src.substring(start, index)
        }

        fun readName(): String {
            if (eof) fail("期望标签名或属性名时遇到文件结尾")
            val first = src[pos]
            if (!(first.isLetter() || first == '_')) {
                fail("标签名 / 属性名必须以字母或下划线开头，实际为 '$first'")
            }
            val start = pos
            while (!eof && isNameChar(src[pos])) advanceChar()
            return src.substring(start, pos)
        }

        fun readAttributeValue(quote: Char, tag: String, name: String): String {
            val sb = StringBuilder()
            while (true) {
                if (eof) fail("属性 '$name' 的取值缺少结束引号 $quote", tag)
                val c = advanceChar()
                when {
                    c == quote -> return sb.toString()
                    c == '&' -> sb.append(readEntity(tag, name))
                    else -> sb.append(c)
                }
            }
        }

        private fun readEntity(tag: String, name: String): String {
            val sb = StringBuilder()
            while (!eof) {
                val c = advanceChar()
                if (c == ';') break
                sb.append(c)
                if (sb.length > 12) fail("属性 '$name' 中存在非法实体", tag)
            }
            val entity = sb.toString()
            return when (entity) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    if (entity.startsWith("#x") || entity.startsWith("#X")) {
                        entity.substring(2).toIntOrNull(16)?.toChar()
                            ?: fail("属性 '$name' 中的数字实体非法: &$entity;", tag)
                    } else if (entity.startsWith("#")) {
                        entity.substring(1).toIntOrNull()?.toChar()
                            ?: fail("属性 '$name' 中的数字实体非法: &$entity;", tag)
                    } else {
                        fail("属性 '$name' 中存在未知实体: &$entity;", tag)
                    }
                }
            }.toString()
        }

        fun expect(char: Char, message: String, tag: String? = null) {
            if (eof || src[pos] != char) fail(message, tag)
            advanceChar()
        }

        fun fail(message: String, tag: String? = null): Nothing =
            throw XmlUiException(message, tag = tag, line = line)
    }
}

private fun isNameChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'
