package com.studyink.reader

import com.studyink.assistant.core.TeacherGptAnswerFormat

/** Builds the self-contained answer body loaded by [FormattedAssistantAnswerView]. */
internal object FormattedAssistantAnswerDocument {
    const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
    const val ASSET_VERSION = "katex-0.18.1-r2"
    const val LOCAL_BASE_URL = "$LOCAL_ORIGIN/gpt-answer/$ASSET_VERSION/"
    const val LOCAL_DOCUMENT_PATH = "document.html"
    const val MAX_SOURCE_CHARS = 120_000

    fun localDocumentUrl(generation: Long): String =
        "$LOCAL_BASE_URL$LOCAL_DOCUMENT_PATH?generation=$generation"

    private const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; " +
            "script-src 'self'; " +
            "style-src 'self' 'unsafe-inline'; " +
            "font-src 'self'; " +
            "img-src data:; " +
            "connect-src 'none'; " +
            "media-src 'none'; " +
            "object-src 'none'; " +
            "frame-src 'none'; " +
            "worker-src 'none'; " +
            "base-uri 'none'; " +
            "form-action 'none'"

    fun build(
        source: String,
        format: TeacherGptAnswerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
    ): String {
        val truncated = source.length > MAX_SOURCE_CHARS
        val normalized = normalizeForDisplay(validUtf16Prefix(source, MAX_SOURCE_CHARS))
        val body = renderBody(normalized, format)
        val truncationNote = if (truncated) {
            "<p class=\"answer-note\">답변이 너무 길어 앞부분만 표시했습니다.</p>"
        } else {
            ""
        }

        return document(body = body + truncationNote, editor = false)
    }

    fun buildEditor(
        source: String,
        format: TeacherGptAnswerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
        hiddenBlockOrdinals: Set<Int> = emptySet(),
    ): String {
        val truncated = source.length > MAX_SOURCE_CHARS
        val boundedSource = validUtf16Prefix(source, MAX_SOURCE_CHARS)
        val blocks = AssistantAnswerBlocks.parse(boundedSource)
            .filterNot { it.ordinal in hiddenBlockOrdinals }
        val body = buildString {
            var remainingMathExpressions = MAX_RENDERED_MATH_EXPRESSIONS
            if (blocks.isEmpty()) {
                append("<p class=\"answer-empty\">남은 답변이 없습니다. 되돌리거나 전체 복원을 눌러주세요.</p>")
            } else {
                blocks.forEach { block ->
                    append("<section class=\"edit-block\" data-block-ordinal=\"")
                        .append(block.ordinal)
                        .append("\">")
                    append("<button class=\"block-check\" type=\"button\" aria-pressed=\"false\" ")
                        .append("aria-label=\"")
                        .append(block.ordinal + 1)
                        .append("번째 줄 선택\"><span aria-hidden=\"true\"></span></button>")
                    append("<div class=\"block-content\">")
                    val normalizedBlock = normalizeForDisplay(block.source)
                    when (format) {
                        TeacherGptAnswerFormat.PLAIN_TEXT ->
                            append("<div class=\"plain-answer\">${escapeHtml(normalizedBlock)}</div>")
                        TeacherGptAnswerFormat.MARKDOWN_TEX -> {
                            val renderer = MarkdownBodyRenderer(normalizedBlock, remainingMathExpressions)
                            append(renderer.render())
                            remainingMathExpressions -= renderer.renderedMathExpressionCount
                        }
                    }
                    append("</div></section>")
                }
            }
            if (truncated) {
                append("<p class=\"answer-note\">답변이 너무 길어 앞부분만 편집합니다.</p>")
            }
        }

        return document(body = body, editor = true)
    }

    private fun renderBody(source: String, format: TeacherGptAnswerFormat): String = when (format) {
        TeacherGptAnswerFormat.PLAIN_TEXT ->
            "<div class=\"plain-answer\">${escapeHtml(source)}</div>"
        TeacherGptAnswerFormat.MARKDOWN_TEX -> MarkdownBodyRenderer(source).render()
    }

    private fun document(body: String, editor: Boolean): String {
        val editorScript = if (editor) "<script defer src=\"editor.js\"></script>" else ""
        val answerClass = if (editor) "answer answer-editor" else "answer"

        return """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
              <meta name="color-scheme" content="light dark">
              <meta http-equiv="Content-Security-Policy" content="$CONTENT_SECURITY_POLICY">
              <link rel="stylesheet" href="katex.min.css">
              <link rel="stylesheet" href="reader.css">
              <script defer src="katex.min.js"></script>
              <script defer src="renderer.js"></script>
              $editorScript
              <title>GPT 답변</title>
            </head>
            <body>
              <main class="$answerClass">$body</main>
            </body>
            </html>
        """.trimIndent()
    }

    fun fallbackText(source: String): String {
        val normalized = normalizeForDisplay(validUtf16Prefix(source, MAX_SOURCE_CHARS))
        return if (source.length > MAX_SOURCE_CHARS) {
            "$normalized\n\n[답변이 너무 길어 앞부분만 표시했습니다.]"
        } else {
            normalized
        }
    }

    internal fun normalizeForDisplay(source: String): String {
        return AssistantAnswerBlocks.normalizeForDisplay(source)
    }

    private class MarkdownBodyRenderer(
        source: String,
        private val mathExpressionLimit: Int = MAX_RENDERED_MATH_EXPRESSIONS,
    ) {
        private val lines = source.split('\n')
        private var index = 0
        private var renderedMathExpressions = 0
        val renderedMathExpressionCount: Int
            get() = renderedMathExpressions

        fun render(): String {
            if (lines.all(String::isBlank)) return "<p class=\"answer-empty\">저장된 답변이 없습니다.</p>"
            return buildString {
                while (index < lines.size) renderNextBlock(this)
            }
        }

        private fun renderNextBlock(output: StringBuilder) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> index += 1
                isFenceStart(trimmed) -> renderCodeBlock(output, trimmed)
                isDisplayMathStart(trimmed) -> renderDisplayMath(output, trimmed)
                headingMatch(line) != null -> renderHeading(output, requireNotNull(headingMatch(line)))
                isHorizontalRule(trimmed) -> {
                    output.append("<hr>")
                    index += 1
                }
                isTableStart(index) -> renderTable(output)
                listMatch(line) != null -> renderList(output, requireNotNull(listMatch(line)))
                quoteText(line) != null -> renderQuote(output)
                else -> renderParagraph(output)
            }
        }

        private fun renderCodeBlock(output: StringBuilder, opening: String) {
            val markerCharacter = opening.first()
            val markerLength = opening.takeWhile { it == markerCharacter }.length.coerceAtLeast(3)
            index += 1
            val code = buildString {
                while (index < lines.size) {
                    val candidate = lines[index].trimStart()
                    if (candidate.takeWhile { it == markerCharacter }.length >= markerLength) {
                        index += 1
                        break
                    }
                    if (isNotEmpty()) append('\n')
                    append(lines[index])
                    index += 1
                }
            }
            output.append("<pre><code>").append(escapeHtml(code)).append("</code></pre>")
        }

        private fun renderDisplayMath(output: StringBuilder, opening: String) {
            val dollarDelimited = opening.startsWith("$$")
            val open = if (dollarDelimited) "$$" else "\\["
            val close = if (dollarDelimited) "$$" else "\\]"
            var remainder = opening.removePrefix(open)
            index += 1

            val math = buildString {
                if (remainder.endsWith(close) && remainder.length >= close.length) {
                    append(remainder.dropLast(close.length).trim())
                    return@buildString
                }
                if (remainder.isNotBlank()) append(remainder.trimStart())
                while (index < lines.size) {
                    val candidate = lines[index]
                    val candidateTrimmed = candidate.trim()
                    if (candidateTrimmed == close) {
                        index += 1
                        break
                    }
                    if (candidateTrimmed.endsWith(close)) {
                        if (isNotEmpty()) append('\n')
                        append(candidateTrimmed.dropLast(close.length).trimEnd())
                        index += 1
                        break
                    }
                    if (isNotEmpty()) append('\n')
                    append(candidate)
                    index += 1
                }
            }
            output.append(mathElement(math.trim(), display = true))
        }

        private fun renderHeading(output: StringBuilder, match: MatchResult) {
            val level = match.groupValues[1].length.coerceIn(1, 6)
            val text = match.groupValues[2].replace(Regex("\\s+#+\\s*$"), "")
            output.append("<h").append(level).append('>')
                .append(renderInline(text))
                .append("</h").append(level).append('>')
            index += 1
        }

        private fun renderTable(output: StringBuilder) {
            val header = AssistantAnswerBlocks.splitTableRow(lines[index])
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && AssistantAnswerBlocks.looksLikeTableRow(lines[index])) {
                rows += AssistantAnswerBlocks.splitTableRow(lines[index])
                index += 1
            }
            output.append("<div class=\"table-scroll\"><table><thead><tr>")
            header.forEach { cell -> output.append("<th>").append(renderInline(cell.trim())).append("</th>") }
            output.append("</tr></thead>")
            if (rows.isNotEmpty()) {
                output.append("<tbody>")
                rows.forEach { row ->
                    output.append("<tr>")
                    repeat(header.size) { column ->
                        output.append("<td>")
                            .append(renderInline(row.getOrElse(column) { "" }.trim()))
                            .append("</td>")
                    }
                    output.append("</tr>")
                }
                output.append("</tbody>")
            }
            output.append("</table></div>")
        }

        private fun renderList(output: StringBuilder, first: ListLine, depth: Int = 0) {
            val tag = if (first.ordered) "ol" else "ul"
            output.append('<').append(tag).append('>')
            while (index < lines.size) {
                val match = listMatch(lines[index]) ?: break
                if (match.indent != first.indent || match.ordered != first.ordered) break
                output.append("<li>").append(renderInline(normalizeTaskMarker(match.text)))
                index += 1
                while (index < lines.size) {
                    val nested = listMatch(lines[index]) ?: break
                    if (nested.indent <= first.indent) break
                    if (depth < MAX_LIST_DEPTH) {
                        renderList(output, nested, depth + 1)
                    } else {
                        output.append("<br>").append(renderInline(normalizeTaskMarker(nested.text)))
                        index += 1
                    }
                }
                output.append("</li>")
            }
            output.append("</").append(tag).append('>')
        }

        private fun renderQuote(output: StringBuilder) {
            val quoted = mutableListOf<String>()
            while (index < lines.size) {
                val text = quoteText(lines[index]) ?: break
                quoted += text
                index += 1
            }
            output.append("<blockquote><p>")
            quoted.forEachIndexed { lineIndex, text ->
                if (lineIndex > 0) output.append("<br>")
                output.append(renderInline(text))
            }
            output.append("</p></blockquote>")
        }

        private fun renderParagraph(output: StringBuilder) {
            val paragraph = mutableListOf<String>()
            while (
                index < lines.size &&
                lines[index].isNotBlank() &&
                (paragraph.isEmpty() || !startsBlock(index))
            ) {
                paragraph += lines[index].trim()
                index += 1
            }
            if (paragraph.isEmpty()) {
                paragraph += lines[index].trim()
                index += 1
            }
            output.append("<p>")
            paragraph.forEachIndexed { lineIndex, text ->
                if (lineIndex > 0) output.append("<br>")
                output.append(renderInline(text))
            }
            output.append("</p>")
        }

        private fun startsBlock(at: Int): Boolean {
            val line = lines[at]
            val trimmed = line.trim()
            return isFenceStart(trimmed) ||
                isDisplayMathStart(trimmed) ||
                headingMatch(line) != null ||
                isHorizontalRule(trimmed) ||
                isTableStart(at) ||
                listMatch(line) != null ||
                quoteText(line) != null
        }

        private fun isTableStart(at: Int): Boolean {
            return AssistantAnswerBlocks.isTableStart(lines, at)
        }

        private fun renderInline(text: String, depth: Int = 0): String {
            if (text.isEmpty()) return ""
            if (depth > MAX_INLINE_DEPTH) return escapeHtml(text)
            return buildString(text.length + 16) {
                var cursor = 0
                while (cursor < text.length) {
                    when {
                        text.startsWith("\\(", cursor) -> {
                            val end = text.indexOf("\\)", cursor + 2)
                            if (end >= 0) {
                                append(mathElement(text.substring(cursor + 2, end), display = false))
                                cursor = end + 2
                            } else {
                                appendEscaped(text[cursor])
                                cursor += 1
                            }
                        }
                        text.startsWith("\$\$", cursor) -> {
                            var end = findUnescaped(text, '$', cursor + 2)
                            while (end >= 0 && text.getOrNull(end + 1) != '$') {
                                end = findUnescaped(text, '$', end + 1)
                            }
                            val source = if (end > cursor + 2) {
                                text.substring(cursor + 2, end).trim()
                            } else {
                                ""
                            }
                            if (source.isNotEmpty()) {
                                // ChatGPT can place display math inside a list item. The DOM-to-
                                // Markdown converter keeps it on that item, so render it inline.
                                append(mathElement(source, display = false))
                                cursor = end + 2
                            } else {
                                append("&#36;&#36;")
                                cursor += 2
                            }
                        }
                        text[cursor] == '$' && text.getOrNull(cursor + 1) != '$' -> {
                            val end = findUnescaped(text, '$', cursor + 1)
                            if (end > cursor + 1 && !text[cursor + 1].isWhitespace() && !text[end - 1].isWhitespace()) {
                                append(mathElement(text.substring(cursor + 1, end), display = false))
                                cursor = end + 1
                            } else {
                                append("&#36;")
                                cursor += 1
                            }
                        }
                        text[cursor] == '`' -> {
                            var markerEnd = cursor
                            while (markerEnd < text.length && text[markerEnd] == '`') markerEnd += 1
                            val markerLength = markerEnd - cursor
                            val marker = "`".repeat(markerLength)
                            val end = text.indexOf(marker, cursor + markerLength)
                            if (end > cursor + markerLength) {
                                val code = text.substring(cursor + markerLength, end).let { value ->
                                    if (value.length >= 2 && value.startsWith(' ') && value.endsWith(' ') && value.isNotBlank()) {
                                        value.substring(1, value.length - 1)
                                    } else {
                                        value
                                    }
                                }
                                append("<code>").append(escapeHtml(code)).append("</code>")
                                cursor = end + markerLength
                            } else {
                                append(escapeHtml(marker))
                                cursor += markerLength
                            }
                        }
                        text.startsWith("**", cursor) || text.startsWith("__", cursor) -> {
                            val marker = text.substring(cursor, cursor + 2)
                            val end = text.indexOf(marker, cursor + 2)
                            if (end > cursor + 2) {
                                append("<strong>")
                                    .append(renderInline(text.substring(cursor + 2, end), depth + 1))
                                    .append("</strong>")
                                cursor = end + 2
                            } else {
                                append(escapeHtml(marker))
                                cursor += 2
                            }
                        }
                        text.startsWith("~~", cursor) -> {
                            val end = text.indexOf("~~", cursor + 2)
                            if (end > cursor + 2) {
                                append("<del>")
                                    .append(renderInline(text.substring(cursor + 2, end), depth + 1))
                                    .append("</del>")
                                cursor = end + 2
                            } else {
                                append("~~")
                                cursor += 2
                            }
                        }
                        text[cursor] == '*' || text[cursor] == '_' -> {
                            val marker = text[cursor]
                            val end = text.indexOf(marker, cursor + 1)
                            if (end > cursor + 1 && !text[cursor + 1].isWhitespace()) {
                                append("<em>")
                                    .append(renderInline(text.substring(cursor + 1, end), depth + 1))
                                    .append("</em>")
                                cursor = end + 1
                            } else {
                                appendEscaped(marker)
                                cursor += 1
                            }
                        }
                        text[cursor] == '[' -> {
                            val labelEnd = text.indexOf(']', cursor + 1)
                            val targetStart = labelEnd + 1
                            if (labelEnd > cursor && text.getOrNull(targetStart) == '(') {
                                val targetEnd = text.indexOf(')', targetStart + 1)
                                if (targetEnd > targetStart) {
                                    append(renderInline(text.substring(cursor + 1, labelEnd), depth + 1))
                                    cursor = targetEnd + 1
                                } else {
                                    append("&#91;")
                                    cursor += 1
                                }
                            } else {
                                append("&#91;")
                                cursor += 1
                            }
                        }
                        text[cursor] == '\\' && cursor + 1 < text.length && text[cursor + 1] in MARKDOWN_ESCAPES -> {
                            appendEscaped(text[cursor + 1])
                            cursor += 2
                        }
                        else -> {
                            appendEscaped(text[cursor])
                            cursor += 1
                        }
                    }
                }
            }
        }

        private fun StringBuilder.appendEscaped(character: Char) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }

        private fun mathElement(source: String, display: Boolean): String {
            val tag = if (display) "div" else "span"
            val cssClass = if (display) "math-display" else "math-inline"
            val open = if (display) "\\[" else "\\("
            val close = if (display) "\\]" else "\\)"
            val wrapped = open + source + close
            if (renderedMathExpressions >= mathExpressionLimit) return escapeHtml(wrapped)
            renderedMathExpressions += 1
            return "<$tag class=\"$cssClass\">${escapeHtml(wrapped)}</$tag>"
        }

        private fun findUnescaped(text: String, character: Char, start: Int): Int {
            var cursor = start
            while (cursor < text.length) {
                if (text[cursor] == character) {
                    var slashes = 0
                    var previous = cursor - 1
                    while (previous >= 0 && text[previous] == '\\') {
                        slashes += 1
                        previous -= 1
                    }
                    if (slashes % 2 == 0) return cursor
                }
                cursor += 1
            }
            return -1
        }

        private fun normalizeTaskMarker(text: String): String = when {
            text.startsWith("[x] ", ignoreCase = true) -> "☑ ${text.drop(4)}"
            text.startsWith("[ ] ") -> "☐ ${text.drop(4)}"
            else -> text
        }

        private fun headingMatch(line: String): MatchResult? = HEADING.matchEntire(line)

        private fun listMatch(line: String): ListLine? {
            UNORDERED_LIST.matchEntire(line)?.let {
                return ListLine(
                    indent = listIndent(it.groupValues[1]),
                    ordered = false,
                    text = it.groupValues[2],
                )
            }
            ORDERED_LIST.matchEntire(line)?.let {
                return ListLine(
                    indent = listIndent(it.groupValues[1]),
                    ordered = true,
                    text = it.groupValues[2],
                )
            }
            return null
        }

        private fun listIndent(value: String): Int = value.sumOf { character ->
            if (character == '\t') 2 else 1
        }

        private fun quoteText(line: String): String? = QUOTE.matchEntire(line)?.groupValues?.get(1)

        private fun isFenceStart(trimmed: String): Boolean =
            trimmed.startsWith("```") || trimmed.startsWith("~~~")

        private fun isDisplayMathStart(trimmed: String): Boolean =
            trimmed.startsWith("$$") || trimmed.startsWith("\\[")

        private fun isHorizontalRule(trimmed: String): Boolean =
            trimmed.matches(Regex("(?:-{3,}|_{3,}|\\*{3,})"))

        private data class ListLine(val indent: Int, val ordered: Boolean, val text: String)
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*$")
    private val UNORDERED_LIST = Regex("^([ \\t]*)[-+*]\\s+(.+?)\\s*$")
    private val ORDERED_LIST = Regex("^([ \\t]*)\\d+[.)]\\s+(.+?)\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*?)\\s*$")
    private val MARKDOWN_ESCAPES = setOf(
        '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '>', '+', '-', '.', '!', '|', '$', '~',
    )
    private const val MAX_INLINE_DEPTH = 12
    private const val MAX_LIST_DEPTH = 12
    private const val MAX_RENDERED_MATH_EXPRESSIONS = 256
}

/** Caps UTF-16 storage without ever leaving an isolated surrogate in persisted/displayed text. */
internal fun validUtf16Prefix(value: String, maxCodeUnits: Int): String {
    require(maxCodeUnits >= 0)
    var end = minOf(value.length, maxCodeUnits)
    if (end in 1 until value.length && Character.isHighSurrogate(value[end - 1]) &&
        Character.isLowSurrogate(value[end])
    ) {
        end -= 1
    }
    return buildString(end) {
        var index = 0
        while (index < end) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) && index + 1 < end &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    append(character)
                    append(value[index + 1])
                    index += 2
                }
                Character.isHighSurrogate(character) || Character.isLowSurrogate(character) -> index += 1
                else -> {
                    append(character)
                    index += 1
                }
            }
        }
    }
}
