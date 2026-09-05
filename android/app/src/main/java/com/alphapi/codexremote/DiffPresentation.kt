package com.alphapi.codexremote

internal enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

internal data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
)

internal data class DiffHunk(
    val header: String,
    val lines: List<DiffLine>,
)

internal data class DiffFile(
    val path: String,
    val headerLines: List<String>,
    val hunks: List<DiffHunk>,
)

internal data class DiffDocument(
    val raw: String,
    val files: List<DiffFile>,
)

private val hunkHeader = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")

internal fun parseUnifiedDiff(raw: String): DiffDocument {
    if (raw.isBlank()) return DiffDocument(raw, emptyList())
    val files = mutableListOf<DiffFile>()
    var filePath = "全部改动"
    var headers = mutableListOf<String>()
    var hunks = mutableListOf<DiffHunk>()
    var hunkTitle: String? = null
    var hunkLines = mutableListOf<DiffLine>()
    var oldLine = 0
    var newLine = 0
    var hasFile = false

    fun finishHunk() {
        val title = hunkTitle ?: return
        hunks += DiffHunk(title, hunkLines.toList())
        hunkTitle = null
        hunkLines = mutableListOf()
    }

    fun finishFile() {
        if (!hasFile && headers.isEmpty() && hunks.isEmpty()) return
        finishHunk()
        files += DiffFile(filePath, headers.toList(), hunks.toList())
        headers = mutableListOf()
        hunks = mutableListOf()
    }

    raw.replace("\r\n", "\n").trimEnd('\n').lines().forEach { line ->
        if (line.startsWith("diff --git ")) {
            finishFile()
            hasFile = true
            val marker = line.lastIndexOf(" b/")
            filePath = if (marker >= 0) line.substring(marker + 3).trim().trim('"') else line
            headers += line
            return@forEach
        }

        val match = hunkHeader.find(line)
        if (match != null) {
            finishHunk()
            hasFile = true
            hunkTitle = line
            oldLine = match.groupValues[1].toInt()
            newLine = match.groupValues[2].toInt()
            return@forEach
        }

        if (hunkTitle == null) {
            hasFile = true
            headers += line
            if (line.startsWith("+++ b/")) filePath = line.removePrefix("+++ b/").trim().trim('"')
            return@forEach
        }

        when {
            line.startsWith("+") && !line.startsWith("+++") -> {
                hunkLines += DiffLine(DiffLineKind.ADDED, line, null, newLine++)
            }
            line.startsWith("-") && !line.startsWith("---") -> {
                hunkLines += DiffLine(DiffLineKind.REMOVED, line, oldLine++, null)
            }
            else -> {
                hunkLines += DiffLine(DiffLineKind.CONTEXT, line, oldLine++, newLine++)
            }
        }
    }
    finishFile()
    return DiffDocument(raw, files)
}
