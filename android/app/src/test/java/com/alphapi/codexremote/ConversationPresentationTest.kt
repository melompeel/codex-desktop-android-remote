package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPresentationTest {
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
    fun allowsOnlyWebLinksFromRenderedMarkdown() {
        assertTrue(isAllowedExternalLink("https://openai.com/docs"))
        assertTrue(isAllowedExternalLink("http://192.168.1.2/help"))
        assertFalse(isAllowedExternalLink("file:///C:/Users/me/.ssh/id_rsa"))
        assertFalse(isAllowedExternalLink("content://private/document"))
        assertFalse(isAllowedExternalLink("intent://settings"))
        assertFalse(isAllowedExternalLink("javascript:alert(1)"))
    }
}
