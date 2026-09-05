package com.alphapi.codexremote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiffViewerDialog(
    taskTitle: String,
    rawDiff: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val document = remember(rawDiff) { rawDiff?.let(::parseUnifiedDiff) }
    var selectedFile by remember(rawDiff) { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("完整改动", maxLines = 1)
                            Text(
                                taskTitle,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭 diff") }
                    },
                    actions = {
                        IconButton(
                            onClick = { rawDiff?.let { clipboard.setText(AnnotatedString(it)) } },
                            enabled = !rawDiff.isNullOrEmpty(),
                        ) { Icon(Icons.Default.ContentCopy, "复制完整 diff") }
                    },
                )
                when {
                    loading && document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在读取改动")
                    }
                    document.files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("当前没有可显示的改动", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            itemsIndexed(document.files) { index, file ->
                                FilterChip(
                                    selected = selectedFile == index,
                                    onClick = { selectedFile = index },
                                    label = { Text(file.path.substringAfterLast('/').substringAfterLast('\\')) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                        DiffFileContent(document.files[selectedFile.coerceIn(document.files.indices)])
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffFileContent(file: DiffFile) {
    val horizontal = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize()) {
        item("path") {
            Text(
                file.path,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (file.headerLines.isNotEmpty()) {
            item("headers") {
                Text(
                    file.headerLines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(horizontal).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        file.hunks.forEachIndexed { hunkIndex, hunk ->
            item("hunk:$hunkIndex") {
                Text(
                    hunk.header,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF285B74),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFEAF3F7)).padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            itemsIndexed(hunk.lines, key = { lineIndex, _ -> "$hunkIndex:$lineIndex" }) { _, line ->
                DiffLineRow(line, horizontal)
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, horizontal: androidx.compose.foundation.ScrollState) {
    val background = when (line.kind) {
        DiffLineKind.ADDED -> Color(0xFFE7F4EA)
        DiffLineKind.REMOVED -> Color(0xFFFBE9E7)
        DiffLineKind.CONTEXT -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(background)) {
        Text(
            line.oldLineNumber?.toString().orEmpty(),
            modifier = Modifier.width(42.dp).padding(horizontal = 5.dp, vertical = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = Color(0xFF777772),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Text(
            line.newLineNumber?.toString().orEmpty(),
            modifier = Modifier.width(42.dp).padding(horizontal = 5.dp, vertical = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = Color(0xFF777772),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            line.text,
            modifier = Modifier.weight(1f).horizontalScroll(horizontal).padding(vertical = 2.dp, horizontal = 4.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            softWrap = false,
        )
    }
}
