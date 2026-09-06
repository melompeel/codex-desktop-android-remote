package com.alphapi.codexremote

import java.net.URI

internal data class ActivityPresentation(
    val title: String,
    val detail: String,
)

internal sealed interface MarkdownSegment {
    data class Prose(val markdown: String) : MarkdownSegment
    data class Code(val language: String?, val code: String) : MarkdownSegment
}

internal fun splitMarkdownSegments(markdown: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val prose = mutableListOf<String>()
    val code = mutableListOf<String>()
    var marker: String? = null
    var language: String? = null

    fun flushProse() {
        val value = prose.joinToString("\n").trim()
        if (value.isNotEmpty()) result += MarkdownSegment.Prose(value)
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
