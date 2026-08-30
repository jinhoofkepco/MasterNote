package com.studyink.reader

import com.studyink.assistant.core.TeacherGptAnswerMask

/**
 * Stable v1 segmentation used by the answer editor and persisted visibility masks.
 *
 * The source remains immutable. Editing only hides whole blocks, so Markdown/TeX delimiters inside
 * a block can never be cut in half by the UI.
 */
internal object AssistantAnswerBlocks {
    const val PARSER_VERSION = TeacherGptAnswerMask.PARSER_VERSION

    fun parse(source: String): List<AssistantAnswerBlock> {
        val normalized = canonicalSource(source)
        if (normalized.isEmpty()) return emptyList()
        val lines = normalized.split('\n')
        val blocks = mutableListOf<AssistantAnswerBlock>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].isBlank()) {
                index += 1
                continue
            }
            val start = index
            val trimmed = lines[index].trim()
            val kind = when {
                fenceMarker(trimmed) != null -> {
                    val marker = requireNotNull(fenceMarker(trimmed))
                    index += 1
                    while (index < lines.size) {
                        val candidate = lines[index].trimStart()
                        index += 1
                        if (candidate.takeWhile { it == marker.first }.length >= marker.second) break
                    }
                    AssistantAnswerBlockKind.STRUCTURE
                }
                displayMathClose(trimmed) != null -> {
                    val close = requireNotNull(displayMathClose(trimmed))
                    val alreadyClosed = trimmed.removePrefix(if (close == "$$") "$$" else "\\[")
                        .trimEnd()
                        .endsWith(close)
                    index += 1
                    if (!alreadyClosed) {
                        while (index < lines.size) {
                            val closed = lines[index].trimEnd().endsWith(close)
                            index += 1
                            if (closed) break
                        }
                    }
                    AssistantAnswerBlockKind.STRUCTURE
                }
                isTableStart(lines, index) -> {
                    index += 2
                    while (index < lines.size && looksLikeTableRow(lines[index])) index += 1
                    AssistantAnswerBlockKind.STRUCTURE
                }
                listKind(lines[index]) != null -> {
                    val listBlockKind = requireNotNull(listKind(lines[index]))
                    index += 1
                    while (index < lines.size && listKind(lines[index]) != null) index += 1
                    listBlockKind
                }
                quoteLine(lines[index]) -> {
                    index += 1
                    while (index < lines.size && quoteLine(lines[index])) index += 1
                    AssistantAnswerBlockKind.QUOTE
                }
                else -> {
                    index += 1
                    AssistantAnswerBlockKind.PROSE
                }
            }
            blocks += AssistantAnswerBlock(
                ordinal = blocks.size,
                source = lines.subList(start, index).joinToString("\n"),
                kind = kind,
                startLineInclusive = start,
                endLineExclusive = index,
            )
        }
        return blocks
    }

    fun visibleSource(source: String, hiddenBlockOrdinals: Set<Int>): String {
        if (hiddenBlockOrdinals.isEmpty()) return source
        val parsed = parse(source)
        val applicableHidden = hiddenBlockOrdinals.intersect(parsed.mapTo(hashSetOf()) { it.ordinal })
        if (applicableHidden.isEmpty()) return source
        val lines = canonicalSource(source).split('\n')
        val hiddenLines = hashSetOf<Int>()
        parsed.filter { it.ordinal in applicableHidden }.forEach { block ->
            hiddenLines += block.startLineInclusive until block.endLineExclusive
            val beforeIsBlank = block.startLineInclusive > 0 &&
                lines[block.startLineInclusive - 1].isBlank()
            val afterIsBlank = block.endLineExclusive < lines.size &&
                lines[block.endLineExclusive].isBlank()
            when {
                afterIsBlank && (beforeIsBlank || block.startLineInclusive == 0) ->
                    hiddenLines += block.endLineExclusive
                beforeIsBlank && block.endLineExclusive == lines.size ->
                    hiddenLines += block.startLineInclusive - 1
            }
        }
        return lines
            .filterIndexed { index, _ -> index !in hiddenLines }
            .joinToString("\n")
            .trim()
    }

    fun visibleSource(source: String, mask: TeacherGptAnswerMask?): String {
        val hidden = mask
            ?.takeIf { it.parserVersion == PARSER_VERSION && it.isValidFor(source) }
            ?.hiddenBlockOrdinals
            .orEmpty()
        return visibleSource(source, hidden)
    }

    internal fun normalizeForDisplay(source: String): String {
        val validSource = validUtf16Prefix(source, source.length)
        return buildString(validSource.length) {
            validSource.replace("\r\n", "\n").replace('\r', '\n').forEach { character ->
                when (character) {
                    '\u00a0' -> append(' ')
                    '\u2028', '\u2029' -> append('\n')
                    '\u00ad',
                    '\u200b',
                    '\u200c',
                    '\u200d',
                    '\u2060',
                    '\ufeff',
                    '\ufffd',
                    '\u202a',
                    '\u202b',
                    '\u202c',
                    '\u202d',
                    '\u202e',
                    '\u2066',
                    '\u2067',
                    '\u2068',
                    '\u2069',
                    -> Unit
                    '\n', '\t' -> append(character)
                    else -> if (character.code >= 0x20 && character.code != 0x7f) append(character)
                }
            }
        }
    }

    private fun canonicalSource(source: String): String = normalizeForDisplay(source).trim()

    private fun fenceMarker(trimmed: String): Pair<Char, Int>? {
        val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val count = trimmed.takeWhile { it == marker }.length
        return if (count >= 3) marker to count else null
    }

    private fun displayMathClose(trimmed: String): String? = when {
        trimmed.startsWith("$$") -> "$$"
        trimmed.startsWith("\\[") -> "\\]"
        else -> null
    }

    internal fun isTableStart(lines: List<String>, index: Int): Boolean =
        index + 1 < lines.size && looksLikeTableRow(lines[index]) &&
            splitTableRow(lines[index + 1]).isNotEmpty() &&
            splitTableRow(lines[index + 1]).all { cell ->
                cell.trim().matches(Regex(":?-{3,}:?"))
            }

    internal fun looksLikeTableRow(line: String): Boolean = splitTableRow(line).size >= 2

    internal fun splitTableRow(line: String): List<String> {
        val content = line.trim().removePrefix("|").removeSuffix("|")
        if (!content.contains('|')) return emptyList()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var escaped = false
        content.forEach { character ->
            when {
                escaped -> {
                    cell.append(character)
                    escaped = false
                }
                character == '\\' -> {
                    cell.append(character)
                    escaped = true
                }
                character == '|' -> {
                    cells += cell.toString()
                    cell.clear()
                }
                else -> cell.append(character)
            }
        }
        cells += cell.toString()
        return cells
    }

    private fun listKind(line: String): AssistantAnswerBlockKind? = when {
        line.matches(Regex("^[ \\t]*[-+*]\\s+.+$")) -> AssistantAnswerBlockKind.UNORDERED_LIST
        line.matches(Regex("^[ \\t]*\\d+[.)]\\s+.+$")) -> AssistantAnswerBlockKind.ORDERED_LIST
        else -> null
    }

    private fun quoteLine(line: String): Boolean = line.matches(Regex("^\\s*>.*$"))
}

internal data class AssistantAnswerBlock(
    val ordinal: Int,
    val source: String,
    val kind: AssistantAnswerBlockKind,
    val startLineInclusive: Int,
    val endLineExclusive: Int,
)

internal enum class AssistantAnswerBlockKind {
    PROSE,
    UNORDERED_LIST,
    ORDERED_LIST,
    QUOTE,
    STRUCTURE,
}
