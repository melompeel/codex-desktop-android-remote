package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspacePresentationTest {
    @Test
    fun groupsTasksByNormalizedWindowsWorkingDirectory() {
        val groups = groupTasksByProject(
            listOf(
                task("live", "C:\\Work\\Demo\\", ownerAvailable = true),
                task("history", "c:/work/demo"),
                task("other", "D:\\Apps\\Other"),
                task("unassigned", null),
            ),
        )

        assertEquals(listOf("Demo", "Other", "未归属"), groups.map { it.name })
        assertEquals(listOf("live", "history"), groups[0].tasks.map { it.threadId })
        assertEquals("c:/work/demo", groups[0].key)
        assertEquals(ProjectGroup.UNASSIGNED_KEY, groups.last().key)
    }

    @Test
    fun choosesTheModelsDefaultWhenThePreviousEffortIsUnsupported() {
        val model = ModelOptionDto(
            id = "gpt-fast",
            displayName = "GPT Fast",
            supportedReasoningEfforts = listOf(
                ReasoningEffortDto("low", "Fast"),
                ReasoningEffortDto("medium", "Balanced"),
            ),
            defaultReasoningEffort = "medium",
        )

        assertEquals("low", compatibleReasoningEffort(model, "low"))
        assertEquals("medium", compatibleReasoningEffort(model, "ultra"))
        assertNull(compatibleReasoningEffort(model.copy(supportedReasoningEfforts = emptyList()), "high"))
    }

    @Test
    fun parsesAUnifiedDiffIntoFilesHunksAndLineKinds() {
        val parsed = parseUnifiedDiff(
            """diff --git a/app/Main.kt b/app/Main.kt
--- a/app/Main.kt
+++ b/app/Main.kt
@@ -1,2 +1,2 @@
-old value
+new value
 unchanged
diff --git a/README.md b/README.md
new file mode 100644
--- /dev/null
+++ b/README.md
@@ -0,0 +1 @@
+hello
""",
        )

        assertEquals(listOf("app/Main.kt", "README.md"), parsed.files.map { it.path })
        assertEquals(
            listOf(DiffLineKind.REMOVED, DiffLineKind.ADDED, DiffLineKind.CONTEXT),
            parsed.files.first().hunks.single().lines.map { it.kind },
        )
        assertEquals(1, parsed.files[1].hunks.single().lines.single().newLineNumber)
    }

    private fun task(id: String, cwd: String?, ownerAvailable: Boolean = false) = TaskDto(
        threadId = id,
        title = id,
        status = if (ownerAvailable) "active" else "idle",
        revision = 1,
        pendingApprovals = 0,
        ownerAvailable = ownerAvailable,
        cwd = cwd,
    )
}
