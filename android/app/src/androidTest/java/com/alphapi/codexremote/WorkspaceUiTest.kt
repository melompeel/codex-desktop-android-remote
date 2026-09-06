package com.alphapi.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class WorkspaceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun explainsWhyDesktopTaskCreationIsUnavailable() {
        compose.setContent {
            MaterialTheme {
                NewTaskDialog(
                    enabled = false,
                    groups = emptyList(),
                    preferredProjectKey = ProjectGroup.ALL_KEY,
                    models = emptyList(),
                    creating = false,
                    onDismiss = {},
                    onCreate = {},
                )
            }
        }

        compose.onNodeWithText("当前连接暂不支持远程新建任务。请先在电脑上新建并打开任务。")
            .assertIsDisplayed()
        compose.onNodeWithText("创建").assertIsNotEnabled()
    }

    @Test
    fun showsPartialCreationErrorWithoutDiscardingThePromptField() {
        compose.setContent {
            MaterialTheme {
                NewTaskDialog(
                    enabled = true,
                    groups = listOf(ProjectGroup("project", "Demo", "C:\\Demo", emptyList())),
                    preferredProjectKey = "project",
                    models = listOf(
                        ModelOptionDto(
                            id = "gpt-test",
                            displayName = "GPT Test",
                            supportedReasoningEfforts = listOf(ReasoningEffortDto("medium")),
                            defaultReasoningEffort = "medium",
                        ),
                    ),
                    creating = false,
                    creationError = "首条指令没有被 Codex 接收。你的指令仍保留在此窗口。",
                    onDismiss = {},
                    onCreate = {},
                )
            }
        }

        compose.onNodeWithText("首条指令没有被 Codex 接收", substring = true).assertIsDisplayed()
        compose.onNodeWithText("任务指令").assertIsDisplayed()
    }

    @Test
    fun showsAParsedFullDiffAndCopyAction() {
        compose.setContent {
            MaterialTheme {
                DiffViewerDialog(
                    taskTitle = "Demo",
                    rawDiff = """diff --git a/a.kt b/a.kt
--- a/a.kt
+++ b/a.kt
@@ -1 +1 @@
-old
+new""",
                    loading = false,
                    onDismiss = {},
                )
            }
        }

        compose.onAllNodesWithText("a.kt").assertCountEquals(2)
        compose.onNodeWithText("+new").assertIsDisplayed()
        compose.onNodeWithContentDescription("复制完整 diff").assertIsDisplayed()
    }

    @Test
    fun keepsEveryApprovalDecisionVisibleOnNarrowScreens() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    ApprovalDecisionActions(
                        enabled = true,
                        onAccept = {},
                        onDecline = {},
                        onCancel = {},
                    )
                }
            }
        }

        compose.onNodeWithText("仅本次允许").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun showsUserNamedServerAddresses() {
        compose.setContent {
            MaterialTheme {
                ConnectionManagerDialog(
                    state = RemoteState(
                        configured = true,
                        serverUrl = "http://100.100.1.2:8766",
                        serverAddresses = listOf(
                            SavedServerAddress("远程连接", "http://100.100.1.2:8766"),
                            SavedServerAddress("工作室", "http://192.168.1.8:8766"),
                        ),
                    ),
                    onDismiss = {},
                    onSwitch = {},
                    onAdd = { _, _ -> },
                    onRemove = {},
                    onClearPairing = {},
                )
            }
        }

        compose.onNodeWithText("远程连接").assertIsDisplayed()
        compose.onNodeWithText("工作室").assertIsDisplayed()
        compose.onNodeWithText("地址名称").assertIsDisplayed()
    }
}
