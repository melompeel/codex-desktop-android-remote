package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPresentationTest {
    @Test
    fun collapsesIntermediateWorkButKeepsUserAndFinalRichReply() {
        val blocks = presentConversation(
            listOf(
                TimelineItemDto("u", "turn-1", "user", "请处理", sourceItemId = "u"),
                TimelineItemDto("thinking", "turn-1", "assistant", "先分析", sourceItemId = "thinking", turnDurationMs = 75432),
                TimelineItemDto("command", "turn-1", "command", "$ npm test", sourceItemId = "command", turnDurationMs = 75432),
                TimelineItemDto("final:text:0", "turn-1", "assistant", "最终结果", sourceItemId = "final", turnDurationMs = 75432),
                TimelineItemDto("final:image:1", "turn-1", "image", "result.png", sourceItemId = "final", turnDurationMs = 75432),
                TimelineItemDto("final:text:2", "turn-1", "assistant", "补充说明", sourceItemId = "final", turnDurationMs = 75432),
            ),
        )

        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is ConversationBlock.Item)
        val process = blocks[1] as ConversationBlock.Process
        assertEquals("用时 1分钟15秒", process.label)
        assertEquals(listOf("thinking", "command"), process.items.map { it.id })
        assertEquals(
            listOf("final:text:0", "final:image:1", "final:text:2"),
            blocks.drop(2).map { (it as ConversationBlock.Item).item.id },
        )
    }

    @Test
    fun usesNeutralProcessLabelWhenDesktopDoesNotProvideDuration() {
        val blocks = presentConversation(
            listOf(
                TimelineItemDto("u", "turn-1", "user", "开始"),
                TimelineItemDto("status", "turn-1", "status", "处理中"),
                TimelineItemDto("final", "turn-1", "assistant", "完成"),
            ),
        )

        assertEquals("处理过程", (blocks[1] as ConversationBlock.Process).label)
    }

    @Test
    fun stripsTerminalEscapeAndControlSequences() {
        val cleaned = sanitizeTerminalText("\u001B[?25l\u001B[2Jfirst\u0000\rsecond\u001BPprivate\u001B\\\n\\\\server\\share\u001B[0m")

        assertEquals("first\nsecond\n\\\\server\\share", cleaned)
        assertFalse(cleaned.contains('\u001B'))
    }

    @Test
    fun summarizesPowerShellAndKeepsItsOutputInTheDisclosure() {
        val item = TimelineItemDto(
            id = "command-1",
            turnId = "turn-1",
            kind = "command",
            text = "$ pwsh.exe -Command npm test\n\n31 tests passed",
        )

        val presented = presentActivity(item)

        assertEquals("运行 npm 命令", presented.title)
        assertTrue(presented.detail.startsWith("$ pwsh.exe"))
        assertTrue(presented.detail.contains("31 tests passed"))
    }

    @Test
    fun summarizesSeveralChangedFilesWithoutDroppingTheirPaths() {
        val item = TimelineItemDto(
            id = "files-1",
            turnId = "turn-1",
            kind = "file",
            text = "app/Main.kt\napp/Theme.kt",
        )

        val presented = presentActivity(item)

        assertEquals("正在编辑文件", presented.title)
        assertEquals("app/Main.kt\napp/Theme.kt", presented.detail)
    }

    @Test
    fun separatesFencedCodeFromMarkdownProse() {
        val segments = splitMarkdownSegments(
            """## Result
This is **ready** with `inline` code.

```kotlin
val answer = 42
```

- complete
""",
        )

        assertEquals(3, segments.size)
        assertEquals("## Result\nThis is **ready** with `inline` code.", (segments[0] as MarkdownSegment.Prose).markdown)
        assertEquals("kotlin", (segments[1] as MarkdownSegment.Code).language)
        assertEquals("val answer = 42", (segments[1] as MarkdownSegment.Code).code)
        assertEquals("- complete", (segments[2] as MarkdownSegment.Prose).markdown)
    }

    @Test
    fun separatesMarkdownTablesFromSurroundingProse() {
        val segments = splitMarkdownSegments(
            """Before

| 方案 | 内存 | 绘制 |
| --- | --- | --- |
| 原表 | 不变 | 一次 |
| 扩展 | 增长 | 多次 |

After""",
        )

        assertTrue(segments[0] is MarkdownSegment.Prose)
        val table = segments[1] as MarkdownSegment.Table
        assertEquals(listOf("方案", "内存", "绘制"), table.header)
        assertEquals(listOf(listOf("原表", "不变", "一次"), listOf("扩展", "增长", "多次")), table.rows)
        assertTrue(segments[2] is MarkdownSegment.Prose)
    }

    @Test
    fun allowsOnlyWebLinksFromRenderedMarkdown() {
        assertTrue(isAllowedExternalLink("https://openai.com/docs"))
        assertTrue(isAllowedExternalLink("http://192.168.1.2/help"))
        assertFalse(isAllowedExternalLink("file:///C:/Users/me/.ssh/id_rsa"))
        assertFalse(isAllowedExternalLink("content://private/document"))
        assertFalse(isAllowedExternalLink("intent://settings"))
        assertFalse(isAllowedExternalLink("javascript:alert(1)"))
    }
}
