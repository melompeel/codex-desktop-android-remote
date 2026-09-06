package com.alphapi.codexremote

import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import android.view.WindowManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPaneTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackReturnsFromConversationToTaskList() {
        var detailOpen by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                RemoteBackNavigation(
                    detailOpen = detailOpen,
                    tab = 0,
                    onCloseDetail = { detailOpen = false },
                    onSelectTasks = {},
                )
                Text(if (detailOpen) "会话详情" else "任务列表")
            }
        }

        compose.onNodeWithText("会话详情").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("任务列表").assertIsDisplayed()
    }

    @Test
    fun keepsComposerVisibleAndToolDetailsCollapsedUntilRequested() {
        var sent = ""
        compose.setContent {
            MaterialTheme {
                var draft by remember { mutableStateOf("") }
                TaskConversationPane(
                    task = TaskDto(
                        threadId = "thread-1",
                        title = "移动端对话测试",
                        status = "idle",
                        revision = 4,
                        pendingApprovals = 0,
                        ownerAvailable = true,
                    ),
                    detail = TaskDetailDto(
                        threadId = "thread-1",
                        title = "移动端对话测试",
                        status = "idle",
                        revision = 4,
                        items = listOf(
                            TimelineItemDto("user-1", "turn-1", "user", "请运行测试"),
                            TimelineItemDto("assistant-1", "turn-1", "assistant", "**正在检查**"),
                            TimelineItemDto(
                                "command-1",
                                "turn-1",
                                "command",
                                "$ npm test\n\u001B[2J33 tests passed",
                                "completed",
                            ),
                            TimelineItemDto(
                                "file-1",
                                "turn-1",
                                "file",
                                "src/MainActivity.kt\nsrc/BridgeApi.kt",
                                "completed",
                            ),
                        ),
                    ),
                    canWrite = true,
                    draft = draft,
                    deliveryMode = DeliveryMode.START,
                    queued = emptyList(),
                    queueReady = false,
                    attachments = emptyList(),
                    models = emptyList(),
                    capabilities = RemoteCapabilitiesDto(),
                    sending = false,
                    stopping = false,
                    onDraftChange = { draft = it },
                    onDeliveryChange = {},
                    onSend = { sent = draft; draft = "" },
                    onStop = {},
                    onCancelQueued = {},
                    onOpenSettings = {},
                    onAttachmentsSelected = {},
                    onRemoveAttachment = {},
                )
            }
        }

        compose.onNodeWithText("给 Codex 发消息").assertIsDisplayed()
        compose.onNodeWithText("运行 npm 命令").assertIsDisplayed()
        compose.onAllNodesWithText("$ npm test", substring = true).assertCountEquals(0)
        compose.onNodeWithText("正在编辑文件").assertIsDisplayed()
        compose.onAllNodesWithText("src/MainActivity.kt", substring = true).assertCountEquals(0)

        compose.onAllNodesWithContentDescription("展开")[0].performClick()
        compose.onNodeWithText("$ npm test", substring = true).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("展开")[0].performClick()
        compose.onNodeWithText("src/MainActivity.kt", substring = true).assertIsDisplayed()

        compose.onNodeWithText("给 Codex 发消息").performTextInput("继续检查")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { assertEquals("继续检查", sent) }
    }

    @Test
    fun exposesSteerQueueStopAndQueuedCancellationWhileTaskIsActive() {
        var delivery = DeliveryMode.STEER
        var stopped = false
        var cancelled = ""
        compose.setContent {
            MaterialTheme {
                TaskConversationPane(
                    task = TaskDto("thread-1", "Active", "active", 2, 0, true),
                    detail = TaskDetailDto("thread-1", "Active", "active", 2),
                    canWrite = true,
                    draft = "补充要求",
                    deliveryMode = delivery,
                    queued = listOf(QueuedFollowUpDto("queued-1", "等当前轮次完成后跑测试")),
                    queueReady = true,
                    attachments = emptyList(),
                    models = emptyList(),
                    capabilities = RemoteCapabilitiesDto(deliveries = listOf("auto", "start", "steer", "queue"), queue = true),
                    sending = false,
                    stopping = false,
                    onDraftChange = {},
                    onDeliveryChange = { delivery = it },
                    onSend = {},
                    onStop = { stopped = true },
                    onCancelQueued = { cancelled = it },
                    onOpenSettings = {},
                    onAttachmentsSelected = {},
                    onRemoveAttachment = {},
                )
            }
        }

        compose.onNodeWithText("调整方向").assertIsDisplayed()
        compose.onNodeWithText("加入队列").performClick()
        compose.onNodeWithContentDescription("停止当前任务").performClick()
        compose.onNodeWithContentDescription("取消排队消息").performClick()

        compose.runOnIdle {
            assertEquals(DeliveryMode.QUEUE, delivery)
            assertEquals(true, stopped)
            assertEquals("queued-1", cancelled)
        }
    }

    @Test
    fun rendersDesktopImagesAndOffersWorkspaceAttachments() {
        var loadRequested = false
        var selectedPath = ""
        val mediaId = "media-1"
        val mediaFile = testPng()
        compose.setContent {
            MaterialTheme {
                TaskConversationPane(
                    task = TaskDto("thread-media", "Media", "idle", 1, 0, true),
                    detail = TaskDetailDto(
                        "thread-media",
                        "Media",
                        "idle",
                        1,
                        items = listOf(
                            TimelineItemDto(
                                id = "image-1",
                                turnId = "turn-1",
                                kind = "image",
                                text = "preview.png",
                                media = TimelineMediaDto(mediaId, "preview.png", "image/png"),
                            ),
                        ),
                    ),
                    canWrite = true,
                    draft = "",
                    deliveryMode = DeliveryMode.START,
                    queued = emptyList(),
                    queueReady = false,
                    attachments = emptyList(),
                    taskMediaById = mapOf(mediaId to mediaFile),
                    workspaceFiles = listOf(
                        WorkspaceFileDto("docs/guide.md", "guide.md", "text/markdown", 128),
                    ),
                    models = emptyList(),
                    capabilities = RemoteCapabilitiesDto(
                        attachments = AttachmentCapabilitiesDto(enabled = true),
                    ),
                    sending = false,
                    stopping = false,
                    onDraftChange = {},
                    onDeliveryChange = {},
                    onSend = {},
                    onStop = {},
                    onCancelQueued = {},
                    onOpenSettings = {},
                    onAttachmentsSelected = {},
                    onRemoveAttachment = {},
                    onLoadWorkspaceFiles = { loadRequested = true },
                    onWorkspaceFileSelected = { selectedPath = it.relativePath },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("timelineImage:$mediaId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("timelineImage:$mediaId").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择电脑文件").performClick()
        compose.onNodeWithText("电脑文件").assertIsDisplayed()
        compose.onNodeWithText("guide.md").performClick()
        compose.runOnIdle {
            assertTrue(loadRequested)
            assertEquals("docs/guide.md", selectedPath)
        }
    }

    @Test
    fun rendersMarkdownTablesAndUserImages() {
        val mediaId = "user-media-1"
        val mediaFile = testPng()
        compose.setContent {
            MaterialTheme {
                TaskConversationPane(
                    task = TaskDto("thread-rich", "Rich", "idle", 1, 0, true),
                    detail = TaskDetailDto(
                        "thread-rich",
                        "Rich",
                        "idle",
                        1,
                        items = listOf(
                            TimelineItemDto(
                                id = "assistant-table",
                                turnId = "turn-1",
                                kind = "assistant",
                                text = "| 方案 | 内存 |\n| --- | --- |\n| 原表 | 不变 |",
                            ),
                            TimelineItemDto(
                                id = "user-image",
                                turnId = "turn-2",
                                kind = "userImage",
                                text = "phone.png",
                                media = TimelineMediaDto(mediaId, "phone.png", "image/png"),
                            ),
                        ),
                    ),
                    canWrite = true,
                    draft = "",
                    deliveryMode = DeliveryMode.START,
                    queued = emptyList(),
                    queueReady = false,
                    attachments = emptyList(),
                    taskMediaById = mapOf(mediaId to mediaFile),
                    models = emptyList(),
                    capabilities = RemoteCapabilitiesDto(),
                    sending = false,
                    stopping = false,
                    onDraftChange = {},
                    onDeliveryChange = {},
                    onSend = {},
                    onStop = {},
                    onCancelQueued = {},
                    onOpenSettings = {},
                    onAttachmentsSelected = {},
                    onRemoveAttachment = {},
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("timelineImage:$mediaId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("markdownTable").assertIsDisplayed()
        compose.onNodeWithText("你发送的图片").assertIsDisplayed()
        compose.onNodeWithTag("timelineImage:$mediaId").assertIsDisplayed()
    }

    @Test
    fun keepsComposerAdjacentToImeWithoutDoubleInset() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        compose.setContent {
            MaterialTheme {
                var draft by remember { mutableStateOf("") }
                TaskConversationPane(
                    task = TaskDto("thread-ime", "IME", "idle", 1, 0, true),
                    detail = TaskDetailDto("thread-ime", "IME", "idle", 1),
                    canWrite = true,
                    draft = draft,
                    deliveryMode = DeliveryMode.START,
                    queued = emptyList(),
                    queueReady = false,
                    attachments = emptyList(),
                    models = emptyList(),
                    capabilities = RemoteCapabilitiesDto(),
                    sending = false,
                    stopping = false,
                    onDraftChange = { draft = it },
                    onDeliveryChange = {},
                    onSend = {},
                    onStop = {},
                    onCancelQueued = {},
                    onOpenSettings = {},
                    onAttachmentsSelected = {},
                    onRemoveAttachment = {},
                )
            }
        }

        compose.onNodeWithText("给 Codex 发消息").performClick()

        var imeBottom = 0
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.activityRule.scenario.onActivity { activity ->
                imeBottom = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())
                    ?.bottom
                    ?: 0
            }
            imeBottom > 0
        }

        val rootBottom = compose.onRoot(useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.bottom
        val composerBottom = compose.onNodeWithTag(
            "messageComposerContent",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot.bottom
        val gap = rootBottom - imeBottom - composerBottom

        assertTrue("Composer-to-IME gap was ${gap}px", gap in 0f..120f)
    }
}

private fun testPng(): File {
    val file = File.createTempFile(
        "preview-",
        ".png",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
    )
    file.outputStream().use { output ->
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
    }
    return file
}
