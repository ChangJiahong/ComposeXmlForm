package cn.changjiahong.composexmlform.engine.parse

import cn.changjiahong.composexmlform.engine.diag.Code
import cn.changjiahong.composexmlform.engine.diag.Diagnostic
import cn.changjiahong.composexmlform.engine.diag.Severity
import cn.changjiahong.composexmlform.engine.diag.Span

/** 一个属性：名字、解码后的值、名字与值在原文中的区间。 */
internal class XmlAttr(
    val name: String,
    val value: String,
    val nameSpan: Span,
    /** 引号**之内**的区间 —— 脚本错误的行列要能映射到这里（D-3）。 */
    val valueSpan: Span,
)

/**
 * 结构解析的产物。**还不是 Node**：这一层只管「XML 是否合法、每个东西在哪」，
 * 不认识标签含义、不认识属性类型。
 */
internal class XmlNode(
    val tag: String,
    val tagSpan: Span,
    val attributes: List<XmlAttr>,
    val children: List<XmlNode>,
    /** 整个元素 `…">…</Tag>`。 */
    val span: Span,
)

internal sealed interface XmlScanResult {

    data class Ok(val root: XmlNode) : XmlScanResult

    data class Err(val diagnostic: Diagnostic) : XmlScanResult
}

/** 阶段 ① 的内部快速失败信号 —— 结构错误之后没有「继续解析」这回事。 */
private class XmlFault(
    message: String,
    val span: Span,
    val tag: String? = null,
) : Exception(message)

/**
 * 严格 XML 扫描器（阶段 ①）。
 *
 * 为什么自写而不是用现成解析器：KMP 三端里 iOS 没有 JVM 的 `javax.xml`，
 * 而 HTML 容错解析器会把「未闭合标签」自动补齐，永远给不出「第 3 行 `<Column>` 未闭合」。
 * 引擎需要把错误如实报给使用者，所以只覆盖「声明 + 注释 + 元素 + 属性」这一小块，
 * 天然跨平台、零依赖。
 *
 * 明确不支持：DOCTYPE、CDATA、处理指令（除 XML 声明）、命名空间、元素文本。
 * 最后一条是设计选择而非能力欠缺 —— 界面用属性描述，文本内容只会是缩进空白。
 *
 * 与旧实现的关键差别：**每个属性带 `nameSpan` / `valueSpan`**。旧版只有元素级行列，
 * 属性名写错时报不出列，而属性名拼错恰恰是最常见的错误。
 */
internal class XmlScanner(private val source: String) {

    private var pos = 0
    private var line = 1
    private var column = 1

    fun scan(): XmlScanResult = try {
        XmlScanResult.Ok(document())
    } catch (fault: XmlFault) {
        XmlScanResult.Err(
            Diagnostic(
                severity = Severity.Error,
                code = Code.XmlMalformed,
                message = fault.message ?: "XML 解析失败",
                span = fault.span,
                tag = fault.tag,
            ),
        )
    }

    // ── 文档 ─────────────────────────────────────────────────

    private fun document(): XmlNode {
        skipBom()
        skipWhitespace()
        if (eof) fail("XML 内容为空，缺少根元素")

        skipProlog()
        if (eof) fail("XML 内容为空，缺少根元素")
        if (peek() != '<') fail("根元素之外存在非法内容『${peek()}』")
        if (startsWith("</")) fail("出现意外的结束标签")

        val root = element()

        // 根之后只允许空白与注释
        while (!eof) {
            when {
                startsWith("<!--") -> skipComment()
                startsWith("<?") -> skipUntil("?>", "XML 声明未闭合")
                peek()?.isWhitespace() == true -> skipWhitespace()
                else -> fail("XML 只允许一个根元素")
            }
        }
        return root
    }

    private fun skipProlog() {
        while (true) {
            when {
                startsWith("<?") -> skipUntil("?>", "XML 声明未闭合")
                startsWith("<!--") -> skipComment()
                startsWith("<!") -> fail("不支持 DOCTYPE 等声明")
                else -> return
            }
            skipWhitespace()
        }
    }

    // ── 元素 ─────────────────────────────────────────────────

    private fun element(): XmlNode {
        val start = mark()
        expect('<', "期望元素起始符 '<'")
        if (peek() == '/') fail("出现意外的结束标签")
        if (peek() == '!') fail("不支持声明或 CDATA 段")

        val tagStart = mark()
        val tag = readName()
        val tagSpan = spanFrom(tagStart)
        if (tag.isEmpty()) fail("标签名为空")

        val attributes = ArrayList<XmlAttr>(4)

        while (true) {
            val hadSpace = skipWhitespace()
            if (eof) fail("标签 <$tag> 未闭合", tag)

            when {
                startsWith("/>") -> {
                    advance()
                    advance()
                    return XmlNode(tag, tagSpan, attributes, emptyList(), spanFrom(start))
                }

                peek() == '>' -> {
                    advance()
                    break
                }

                else -> {
                    if (!hadSpace) fail("标签 <$tag> 的属性之间缺少空格", tag)
                    attributes += attribute(tag, attributes)
                }
            }
        }

        val children = ArrayList<XmlNode>(4)
        while (true) {
            if (eof) fail("标签 <$tag> 缺少结束标签 </$tag>", tag)

            when {
                startsWith("</") -> {
                    advance()
                    advance()
                    val closeTag = readName()
                    skipWhitespace()
                    expect('>', "结束标签 </$closeTag> 格式错误", tag)
                    if (closeTag != tag) {
                        fail("结束标签 </$closeTag> 与开始标签 <$tag> 不匹配", tag)
                    }
                    return XmlNode(tag, tagSpan, attributes, children, spanFrom(start))
                }

                startsWith("<!--") -> skipComment()

                startsWith("<![CDATA[") -> fail("不支持 CDATA 段", tag)

                peek() == '<' -> children += element()

                else -> {
                    val textStart = mark()
                    val text = StringBuilder()
                    while (!eof && peek() != '<') text.append(advance())
                    if (text.isNotBlank()) {
                        throw XmlFault(
                            "不支持元素文本内容，界面请用属性描述（例如 <Text text=\"...\"/>）；" +
                                "实际文本：『${text.toString().trim().take(30)}』",
                            spanFrom(textStart),
                            tag,
                        )
                    }
                }
            }
        }
    }

    private fun attribute(tag: String, existing: List<XmlAttr>): XmlAttr {
        val nameStart = mark()
        val name = readName()
        val nameSpan = spanFrom(nameStart)
        if (name.isEmpty()) fail("属性名为空", tag)

        skipWhitespace()
        expect('=', "属性『$name』缺少 '=' 与取值", tag)
        skipWhitespace()

        val quote = peek() ?: fail("属性『$name』缺少取值", tag)
        if (quote != '"' && quote != '\'') {
            fail("属性『$name』的值必须用双引号或单引号包裹", tag)
        }
        advance()

        val valueStart = mark()
        val value = readAttributeValue(quote, tag, name)
        val valueSpan = spanFrom(valueStart)

        if (existing.any { it.name == name }) fail("标签 <$tag> 出现重复属性『$name』", tag)

        return XmlAttr(name, value, nameSpan, valueSpan)
    }

    private fun readAttributeValue(quote: Char, tag: String, name: String): String {
        val out = StringBuilder()
        while (true) {
            if (eof) fail("属性『$name』的取值缺少结束引号 $quote", tag)
            val c = advance()
            when {
                c == quote -> return out.toString()
                c == '&' -> out.append(readEntity(tag, name))
                c == '<' -> fail("属性『$name』的值里不能出现未转义的 '<'", tag)
                else -> out.append(c)
            }
        }
    }

    /** 实体解码。只认 XML 五个预定义实体与数字实体 —— 命名空间与 DTD 不在支持范围。 */
    private fun readEntity(tag: String, name: String): String {
        val out = StringBuilder()
        while (!eof) {
            val c = advance()
            if (c == ';') break
            out.append(c)
            if (out.length > 12) fail("属性『$name』中存在非法实体", tag)
        }
        val entity = out.toString()
        return when {
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "amp" -> "&"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)?.toChar()
                    ?: fail("属性『$name』中的数字实体非法：&$entity;", tag)

            entity.startsWith("#") ->
                entity.substring(1).toIntOrNull()?.toChar()
                    ?: fail("属性『$name』中的数字实体非法：&$entity;", tag)

            else -> fail("属性『$name』中存在未知实体：&$entity;", tag)
        }.toString()
    }

    // ── 词法 ─────────────────────────────────────────────────

    private data class Mark(val pos: Int, val line: Int, val column: Int)

    private fun mark(): Mark = Mark(pos, line, column)

    private fun spanFrom(start: Mark): Span = Span(start.pos, pos, start.line, start.column)

    private val eof: Boolean get() = pos >= source.length

    private fun peek(offset: Int = 0): Char? {
        val index = pos + offset
        return if (index < source.length) source[index] else null
    }

    private fun startsWith(text: String): Boolean =
        pos + text.length <= source.length && source.regionMatches(pos, text, 0, text.length)

    private fun advance(): Char {
        val c = source[pos]
        pos++
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }

    private fun skipBom() {
        if (!eof && source[pos] == '\uFEFF') advance()
    }

    private fun skipWhitespace(): Boolean {
        var moved = false
        while (!eof && source[pos].isWhitespace()) {
            advance()
            moved = true
        }
        return moved
    }

    private fun skipUntil(marker: String, message: String) {
        val end = source.indexOf(marker, pos)
        if (end < 0) fail(message)
        while (pos < end + marker.length) advance()
    }

    private fun skipComment() {
        val start = mark()
        repeat(4) { advance() } // <!--
        val end = source.indexOf("-->", pos)
        if (end < 0) {
            throw XmlFault("注释未闭合（缺少 -->）", spanFrom(start))
        }
        while (pos < end + 3) advance()
    }

    private fun readName(): String {
        if (eof) fail("期望标签名或属性名时遇到文末")
        val first = source[pos]
        if (!first.isLetter() && first != '_') {
            fail("标签名 / 属性名必须以字母或下划线开头，实际为『$first』")
        }
        val start = pos
        while (!eof && isNameChar(source[pos])) advance()
        return source.substring(start, pos)
    }

    private fun expect(char: Char, message: String, tag: String? = null) {
        if (eof || source[pos] != char) fail(message, tag)
        advance()
    }

    private fun fail(message: String, tag: String? = null): Nothing =
        throw XmlFault(message, spanFrom(mark()), tag)
}

private fun isNameChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'
