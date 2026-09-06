package com.alphapi.codexremote

import java.net.URI

internal data class ActivityPresentation(
    val title: String,
    val detail: String,
)

internal sealed interface ConversationBlock {
    data class Item(val item: TimelineItemDto) : ConversationBlock
    data class Process(
        val turnId: String,
        val label: String,
        val items: List<TimelineItemDto>,
    ) : ConversationBlock
}

internal fun presentConversation(items: List<TimelineItemDto>): List<ConversationBlock> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<ConversationBlock>()
    var cursor = 0
    while (cursor < items.size) {
        val turnId = items[cursor].turnId
        val end = items.indexOfFirst(cursor) { it.turnId != turnId }
            .takeIf { it >= 0 }
            ?: items.size
        result += presentTurn(items.subList(cursor, end))
        cursor = end
    }
    return result
}

private fun presentTurn(items: List<TimelineItemDto>): List<ConversationBlock> {
    val finalAnchor = items.indexOfLast { it.kind == "assistant" }
        .takeIf { it >= 0 }
        ?: items.indexOfLast { it.kind == "image" }.takeIf { it >= 0 }
    val finalSource = finalAnchor?.let { sourceItemId(items[it]) }
    val durationMs = items.firstNotNullOfOrNull { it.turnDurationMs }
    val result = mutableListOf<ConversationBlock>()
    val process = mutableListOf<TimelineItemDto>()

    fun flushProcess() {
        if (process.isEmpty()) return
        result += ConversationBlock.Process(
            turnId = items.first().turnId,
            label = durationMs?.let(::formatTurnDuration) ?: "处理过程",
            items = process.toList(),
        )
        process.clear()
    }

    items.forEach { item ->
        val visible = item.kind == "user" || item.kind == "userImage" ||
            (finalSource != null && sourceItemId(item) == finalSource &&
                (item.kind == "assistant" || item.kind == "image"))
        if (visible) {
            flushProcess()
            result += ConversationBlock.Item(item)
        } else {
            process += item
        }
    }
    flushProcess()
    return result
}

private fun sourceItemId(item: TimelineItemDto): String = item.sourceItemId
    ?: item.id.substringBefore(":text:").substringBefore(":image:")

private fun formatTurnDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return buildString {
        append("用时 ")
        if (hours > 0) append(hours).append("小时")
        if (minutes > 0 || hours > 0) append(minutes).append("分钟")
        append(seconds).append("秒")
    }
}

private inline fun <T> List<T>.indexOfFirst(
    startIndex: Int,
    predicate: (T) -> Boolean,
): Int {
    for (index in startIndex until size) if (predicate(this[index])) return index
    return -1
}

internal sealed interface MarkdownSegment {
    data class Prose(val markdown: String) : MarkdownSegment
    data class Code(val language: String?, val code: String) : MarkdownSegment
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownSegment
}

internal fun splitMarkdownSegments(markdown: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val prose = mutableListOf<String>()
    val code = mutableListOf<String>()
    var marker: String? = null
    var language: String? = null

    fun flushProse() {
        result += splitProseTables(prose)
        prose.clear()
    }

    fun flushCode() {
        result += MarkdownSegment.Code(language, code.joinToString("\n").trimEnd())
        code.clear()
        marker = null
        language = null
    }

    markdown.replace("\r\n", "\n").lines().forEach { line ->
        val trimmed = line.trimStart()
        val activeMarker = marker
        if (activeMarker == null && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
            flushProse()
            marker = trimmed.take(3)
            language = trimmed.drop(3).trim().takeIf(String::isNotEmpty)
        } else if (activeMarker != null && trimmed.startsWith(activeMarker)) {
            flushCode()
        } else if (activeMarker != null) {
            code += line
        } else {
            prose += line
        }
    }
    if (marker != null) flushCode() else flushProse()
    return result
}

private fun splitProseTables(lines: List<String>): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val prose = mutableListOf<String>()

    fun flushProse() {
        val value = prose.joinToString("\n").trim()
        if (value.isNotEmpty()) result += MarkdownSegment.Prose(value)
        prose.clear()
    }

    var index = 0
    while (index < lines.size) {
        val header = parseMarkdownTableRow(lines[index])
        val separator = lines.getOrNull(index + 1)?.let(::parseMarkdownTableRow)
        val isTable = header != null && separator != null &&
            header.size == separator.size && separator.all(::isMarkdownTableSeparator)
        if (!isTable) {
            prose += lines[index]
            index += 1
            continue
        }

        flushProse()
        val rows = mutableListOf<List<String>>()
        index += 2
        while (index < lines.size) {
            val row = parseMarkdownTableRow(lines[index])
                ?.takeIf { it.size == header!!.size }
                ?: break
            rows += row
            index += 1
        }
        result += MarkdownSegment.Table(header!!, rows)
    }
    flushProse()
    return result
}

private fun parseMarkdownTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if ('|' !in trimmed) return null
    val content = trimmed
        .removePrefix("|")
        .removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    content.forEach { character ->
        if (escaped) {
            if (character == '|') current.append('|')
            else {
                current.append('\\')
                current.append(character)
            }
            escaped = false
            return@forEach
        }
        when {
            character == '\\' -> escaped = true
            character == '|' -> {
                cells += current.toString().trim()
                current.clear()
            }
            else -> current.append(character)
        }
    }
    if (escaped) current.append('\\')
    cells += current.toString().trim()
    return cells.takeIf { it.size >= 2 }
}

private fun isMarkdownTableSeparator(value: String): Boolean =
    value.replace(" ", "").matches(Regex(":?-{3,}:?"))

internal fun isAllowedExternalLink(value: String): Boolean = runCatching {
    URI(value).scheme?.lowercase() in setOf("http", "https")
}.getOrDefault(false)

private val ansiCsi = Regex("(?:\\u001B\\[|\\u009B)[0-?]*[ -/]*[@-~]")
private val ansiOsc = Regex("(?:\\u001B\\]|\\u009D).*?(?:\\u0007|\\u001B\\\\|\\u009C)", RegexOption.DOT_MATCHES_ALL)
private val ansiControlString = Regex("(?:\\u001B[PX^_]|[\\u0090\\u0098\\u009E\\u009F]).*?(?:\\u001B\\\\|\\u009C)", RegexOption.DOT_MATCHES_ALL)
private val ansiSingleEscape = Regex("\\u001B[ -/]*[@-~]")
private val unsafeControlCharacter = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
private val c1ControlCharacter = Regex("[\\u0080-\\u009F]")
private val printedAnsiEscape = Regex("\\\\u001[bB](?:\\[[0-?]*[ -/]*[@-~]|\\][^\\n]*(?:\\\\u0007|\\\\u001[bB]\\\\\\\\))")

internal fun sanitizeTerminalText(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .replace(ansiOsc, "")
    .replace(ansiControlString, "")
    .replace(ansiCsi, "")
    .replace(ansiSingleEscape, "")
    .replace(printedAnsiEscape, "")
    .replace(c1ControlCharacter, "")
    .replace(unsafeControlCharacter, "")
    .trim()

internal fun presentActivity(item: TimelineItemDto): ActivityPresentation {
    val cleaned = sanitizeTerminalText(item.text)
    return when (item.kind) {
        "command" -> presentCommand(cleaned)
        "file" -> presentFiles(cleaned)
        else -> ActivityPresentation(activityTitle(item.kind), cleaned)
    }
}

private fun presentCommand(text: String): ActivityPresentation {
    val lines = text.lines().dropWhile(String::isBlank)
    if (lines.isEmpty()) return ActivityPresentation("运行命令", "")
    val command = lines.first().removePrefix("$ ").trim()
    val output = lines.drop(1).joinToString("\n").trim()
    val title = when {
        command.contains("gradlew", ignoreCase = true) -> "运行 Android 构建"
        command.contains("npm", ignoreCase = true) -> "运行 npm 命令"
        command.contains("powershell", ignoreCase = true) ||
            command.contains("pwsh", ignoreCase = true) -> "运行 PowerShell"
        command.contains("git ", ignoreCase = true) -> "运行 Git 命令"
        command.isBlank() -> "运行命令"
        else -> "运行命令"
    }
    val detail = listOfNotNull(
        command.takeIf(String::isNotBlank)?.let { "$ $it" },
        output.takeIf(String::isNotBlank),
    ).joinToString("\n\n")
    return ActivityPresentation(title, detail)
}

private fun presentFiles(text: String): ActivityPresentation {
    val paths = text.lines().map(String::trim).filter(String::isNotBlank)
    return ActivityPresentation("正在编辑文件", paths.joinToString("\n"))
}

internal fun activityTitle(kind: String): String = when (kind) {
    "command" -> "运行命令"
    "file" -> "文件已更新"
    "plan" -> "计划"
    else -> "工作进展"
}
