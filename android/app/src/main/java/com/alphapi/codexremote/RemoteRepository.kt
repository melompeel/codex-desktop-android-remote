package com.alphapi.codexremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket

class RemoteRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val store = CredentialStore(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = mutableState.asStateFlow()
    private var api: BridgeApi? = null
    private var stream: WebSocket? = null
    private var reconnectJob: Job? = null
    private var refreshCoordinator: ConflatedRefreshCoordinator? = null
    private var restoreJob: Job? = null
    private var reconnectAttempt = 0
    private var streamGeneration = 0
    private var followedThreadId: String? = null

    fun restoreAndStart(startService: Boolean = true) {
        if (api != null) {
            if (startService) startListenerService()
            return
        }
        if (restoreJob?.isActive == true) return
        restoreJob = scope.launch {
            val saved = store.load() ?: return@launch
            if (api == null) {
                configure(saved)
                refresh()
                openStream()
            }
            if (startService) startListenerService()
        }
    }

    fun pair(serverUrl: String, code: String, deviceName: String) {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val normalized = BridgeEndpoint.normalize(serverUrl)
                val response = BridgeApi(normalized, null).pair(code.trim(), deviceName.trim())
                val saved = StoredConnection(normalized, response.deviceId, response.token)
                store.save(saved)
                configure(saved)
                openStream()
                startListenerService()
                refreshNow()
            }.onFailure { error -> update { it.copy(error = error.message) } }
            update { it.copy(loading = false) }
        }
    }

    fun refresh() {
        scheduleRefresh(0)
    }

    fun select(threadId: String) {
        update { it.copy(selectedThreadId = threadId, taskDetail = null) }
        scope.launch {
            update { it.copy(loading = true, error = null) }
            val followFailure = runCatching {
                requireApi().follow(threadId)
                followedThreadId = threadId
            }.exceptionOrNull()
            runCatching { refreshNow() }.onFailure(::recordError)
            if (followFailure != null) {
                update {
                    it.copy(error = "桌面未打开此任务，当前只能查看历史记录")
                }
            }
            update { it.copy(loading = false) }
        }
    }

    fun sendMessage(threadId: String, text: String, onSent: (() -> Unit)? = null) = action(onSent) {
        requireApi().sendMessage(threadId, text)
    }

    fun updateDraft(threadId: String, value: String) {
        update { state -> state.copy(draftsByThread = state.draftsByThread + (threadId to value)) }
    }

    fun setDelivery(threadId: String, delivery: DeliveryMode) {
        update { state -> state.copy(deliveryByThread = state.deliveryByThread + (threadId to delivery)) }
    }

    fun sendDraft(threadId: String) {
        val snapshot = mutableState.value
        val text = snapshot.draftsByThread[threadId].orEmpty().trim()
        val attachments = snapshot.attachmentsByThread[threadId].orEmpty()
        val attachmentIds = attachments.mapNotNull { it.attachmentId }
        if ((text.isEmpty() && attachmentIds.isEmpty()) || attachments.any { it.uploadState != AttachmentUploadState.READY }) return
        val task = snapshot.tasks.firstOrNull { it.threadId == threadId } ?: return
        val requestedDelivery = snapshot.deliveryByThread[threadId] ?: defaultDeliveryFor(task.status)
        if (requestedDelivery == DeliveryMode.QUEUE && attachments.isNotEmpty()) return
        val delivery = if (snapshot.capabilities.explicitDelivery) requestedDelivery else null
        if (delivery == DeliveryMode.STEER && task.activeTurnId == null) {
            update { it.copy(error = "当前轮次状态尚未同步，请刷新后重试") }
            return
        }
        if (delivery == DeliveryMode.QUEUE && snapshot.queueHashByThread[threadId] == null) {
            update { it.copy(error = "排队状态尚未同步，请刷新后重试") }
            return
        }
        scope.launch {
            update { it.copy(sendingThreads = it.sendingThreads + threadId, error = null) }
            runCatching {
                requireApi().sendMessage(
                    threadId = threadId,
                    text = text,
                    delivery = delivery,
                    expectedTurnId = task.activeTurnId,
                    expectedQueueHash = snapshot.queueHashByThread[threadId],
                    attachmentIds = attachmentIds,
                )
                update { state ->
                    state.copy(
                        draftsByThread = state.draftsByThread + (threadId to ""),
                        attachmentsByThread = state.attachmentsByThread - threadId,
                    )
                }
                refreshNow()
            }.onFailure(::recordError)
            update { it.copy(sendingThreads = it.sendingThreads - threadId) }
        }
    }

    fun interrupt(threadId: String) {
        scope.launch {
            update { it.copy(stoppingThreads = it.stoppingThreads + threadId, error = null) }
            runCatching {
                requireApi().interrupt(threadId)
                refreshNow()
            }.onFailure(::recordError)
            update { it.copy(stoppingThreads = it.stoppingThreads - threadId) }
        }
    }

    fun cancelQueuedMessage(threadId: String, messageId: String) {
        val expectedHash = mutableState.value.queueHashByThread[threadId] ?: return
        scope.launch {
            runCatching {
                requireApi().cancelQueuedMessage(threadId, messageId, expectedHash)
                refreshNow()
            }.onFailure(::recordError)
        }
    }

    fun updateSettings(threadId: String, model: String, effort: String) {
        scope.launch {
            update { it.copy(settingsThreads = it.settingsThreads + threadId, error = null) }
            runCatching {
                requireApi().updateSettings(threadId, model, effort)
                refreshNow()
            }.onFailure(::recordError)
            update { it.copy(settingsThreads = it.settingsThreads - threadId) }
        }
    }

    fun loadDiff(threadId: String) {
        scope.launch {
            update { it.copy(loadingDiffThreads = it.loadingDiffThreads + threadId, error = null) }
            runCatching { requireApi().diff(threadId) }
                .onSuccess { diff -> update { it.copy(diffByThread = it.diffByThread + (threadId to diff.unifiedDiff())) } }
                .onFailure(::recordError)
            update { it.copy(loadingDiffThreads = it.loadingDiffThreads - threadId) }
        }
    }

    fun createTask(draft: CreateTaskDraft, onCreated: (() -> Unit)? = null) {
        if (!mutableState.value.capabilities.newTask) {
            update { it.copy(taskCreationError = "当前 Codex Desktop 版本尚未开放远程新建任务") }
            return
        }
        scope.launch {
            update { it.copy(creatingTask = true, taskCreationError = null, error = null) }
            runCatching { requireApi().createTask(draft) }
                .onSuccess { response ->
                    refreshNow()
                    val threadId = response.task?.threadId ?: response.threadId
                    if (threadId != null) select(threadId)
                    val disposition = response.disposition()
                    if (disposition.closeDialog) {
                        if (onCreated != null) withContext(Dispatchers.Main.immediate) { onCreated() }
                    } else {
                        update { it.copy(taskCreationError = disposition.message) }
                    }
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        update { it.copy(taskCreationError = error.message ?: "新建任务失败") }
                    }
                }
            update { it.copy(creatingTask = false) }
        }
    }

    fun clearTaskCreationError() {
        update { it.copy(taskCreationError = null) }
    }

    fun addAttachments(threadId: String, uris: List<Uri>) {
        val snapshot = mutableState.value
        if (!snapshot.capabilities.attachments.enabled || uris.isEmpty()) return
        val existing = snapshot.attachmentsByThread[threadId].orEmpty()
        val availableSlots = (MAX_COMPOSER_ATTACHMENTS - existing.size).coerceAtLeast(0)
        uris.take(availableSlots).forEach { uri ->
            val metadata = readAttachmentMetadata(uri)
            val localId = UUID.randomUUID().toString()
            val pending = ComposerAttachment(
                localId = localId,
                uri = uri.toString(),
                name = metadata.name,
                mimeType = metadata.mimeType,
                size = metadata.size,
                uploadState = AttachmentUploadState.UPLOADING,
            )
            update { state ->
                val current = state.attachmentsByThread[threadId].orEmpty()
                state.copy(attachmentsByThread = state.attachmentsByThread + (threadId to (current + pending)))
            }
            scope.launch { uploadAttachment(threadId, pending, uri) }
        }
    }

    fun removeAttachment(threadId: String, localId: String) {
        val attachment = mutableState.value.attachmentsByThread[threadId].orEmpty()
            .firstOrNull { it.localId == localId }
        update { state ->
            val remaining = state.attachmentsByThread[threadId].orEmpty().filterNot { it.localId == localId }
            state.copy(attachmentsByThread = if (remaining.isEmpty()) {
                state.attachmentsByThread - threadId
            } else state.attachmentsByThread + (threadId to remaining))
        }
        attachment?.attachmentId?.let { id -> scope.launch { runCatching { requireApi().deleteAttachment(id) } } }
    }

    private suspend fun uploadAttachment(threadId: String, pending: ComposerAttachment, uri: Uri) {
        runCatching {
            val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("无法读取附件")
            val limit = mutableState.value.capabilities.maxAttachmentBytes
            if (bytes.size.toLong() > limit) {
                throw IllegalArgumentException("附件超过 ${formatFileSize(limit)} 限制")
            }
            requireApi().uploadAttachment(pending.name, pending.mimeType, bytes)
        }.onSuccess { uploaded ->
            replaceAttachment(threadId, pending.localId) {
                it.copy(
                    name = uploaded.name.ifBlank { it.name },
                    mimeType = uploaded.mimeType.ifBlank { it.mimeType },
                    size = uploaded.size.takeIf { size -> size > 0 } ?: it.size,
                    uploadState = AttachmentUploadState.READY,
                    attachmentId = uploaded.attachmentId,
                    error = null,
                )
            }
        }.onFailure { error ->
            replaceAttachment(threadId, pending.localId) {
                it.copy(uploadState = AttachmentUploadState.FAILED, error = error.message ?: "上传失败")
            }
        }
    }

    private fun replaceAttachment(
        threadId: String,
        localId: String,
        transform: (ComposerAttachment) -> ComposerAttachment,
    ) {
        update { state ->
            val attachments = state.attachmentsByThread[threadId].orEmpty().map {
                if (it.localId == localId) transform(it) else it
            }
            state.copy(attachmentsByThread = state.attachmentsByThread + (threadId to attachments))
        }
    }

    private fun readAttachmentMetadata(uri: Uri): LocalAttachmentMetadata {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "attachment" }
        var size = 0L
        applicationContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    cursor.getString(index)?.takeIf(String::isNotBlank)?.let { name = it }
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                    if (!cursor.isNull(index)) size = cursor.getLong(index).coerceAtLeast(0)
                }
            }
        }
        return LocalAttachmentMetadata(
            name = name,
            mimeType = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream",
            size = size,
        )
    }

    fun requestPush(threadId: String) = action { requireApi().requestPush(threadId) }

    fun respondApproval(requestId: String, decision: String) = action {
        requireApi().respondApproval(requestId, decision)
    }

    fun respondUserInput(requestId: String, answers: Map<String, List<String>>) = action {
        requireApi().respondUserInput(requestId, answers)
    }

    fun disconnect() {
        val previousApi = api
        stream?.cancel()
        stream = null
        reconnectJob?.cancel()
        reconnectJob = null
        refreshCoordinator?.dispose()
        refreshCoordinator = null
        restoreJob?.cancel()
        restoreJob = null
        streamGeneration += 1
        followedThreadId = null
        api = null
        store.clear()
        applicationContext.stopService(Intent(applicationContext, RemoteService::class.java))
        mutableState.value = RemoteState()
        scope.launch { runCatching { previousApi?.revokeSelf() } }
    }

    private fun configure(saved: StoredConnection) {
        refreshCoordinator?.dispose()
        refreshCoordinator = ConflatedRefreshCoordinator(scope) {
            runCatching { refreshNow() }.onFailure(::recordError)
        }
        api = BridgeApi(saved.serverUrl, saved.token)
        update { it.copy(configured = true, serverUrl = saved.serverUrl, error = null) }
    }

    private fun openStream() {
        reconnectJob?.cancel()
        reconnectJob = null
        val generation = ++streamGeneration
        stream?.cancel()
        stream = requireApi().stream(
            onEvent = {
                if (generation == streamGeneration) scheduleRefresh(150)
            },
            onConnected = { connected ->
                if (generation != streamGeneration) return@stream
                update { it.copy(connected = connected) }
                if (connected) {
                    reconnectAttempt = 0
                    scheduleRefresh(0)
                } else {
                    followedThreadId = null
                    scheduleReconnect(generation)
                }
            },
        )
    }

    private fun scheduleReconnect(generation: Int) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delayMs = minOf(30_000L, 1_000L shl minOf(reconnectAttempt, 5))
            reconnectAttempt += 1
            delay(delayMs)
            if (generation == streamGeneration && api != null) {
                openStream()
                runCatching { refreshNow() }.onFailure(::recordError)
            }
        }
    }

    private suspend fun refreshNow() {
        val bridge = requireApi()
        val health = bridge.health()
        val requestedThreadId = mutableState.value.selectedThreadId
        if (
            health.ipc == "connected" &&
            requestedThreadId != null &&
            followedThreadId != requestedThreadId
        ) {
            runCatching { bridge.follow(requestedThreadId) }
                .onSuccess { followedThreadId = requestedThreadId }
        }
        val capabilities = runCatching { bridge.capabilities() }.getOrDefault(RemoteCapabilitiesDto())
        val models = runCatching { bridge.models() }.getOrDefault(emptyList())
        val tasks = bridge.tasks()
        val approvals = bridge.approvals()
        val current = mutableState.value
        val selectedThreadId = current.selectedThreadId
            ?.takeIf { selected -> tasks.any { it.threadId == selected } }
            ?: tasks.firstOrNull { it.ownerAvailable }?.threadId
            ?: tasks.firstOrNull()?.threadId
        val detail = selectedThreadId?.let { selected ->
            runCatching { bridge.taskDetail(selected) }.getOrNull()
        }
        val queue = if (selectedThreadId != null && capabilities.queue) {
            runCatching { bridge.queue(selectedThreadId) }.getOrNull()
        } else null
        update { current ->
            current.copy(
                configured = true,
                tasks = tasks,
                taskDetail = detail,
                approvals = approvals,
                selectedThreadId = selectedThreadId,
                writeSupported = health.compatibility.supported,
                compatibilityVerified = health.compatibility.verified,
                ipcConnected = health.ipc == "connected",
                desktopVersion = health.compatibility.installed,
                capabilities = capabilities,
                models = models,
                queueByThread = if (selectedThreadId != null && queue != null) {
                    current.queueByThread + (selectedThreadId to queue.values())
                } else current.queueByThread,
                queueHashByThread = if (selectedThreadId != null && queue != null) {
                    current.queueHashByThread + (selectedThreadId to queue.hash)
                } else current.queueHashByThread,
                error = null,
            )
        }
    }

    private fun action(onSuccess: (() -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                block()
                refreshNow()
            }.onSuccess {
                if (onSuccess != null) withContext(Dispatchers.Main.immediate) { onSuccess() }
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    private fun requireApi(): BridgeApi = requireNotNull(api) { "Bridge is not configured" }
    private fun recordError(error: Throwable) {
        if (error is CancellationException) return
        update { it.copy(error = error.message) }
    }
    private fun update(block: (RemoteState) -> RemoteState) { mutableState.value = block(mutableState.value) }

    private fun scheduleRefresh(delayMs: Long) {
        refreshCoordinator?.request(delayMs)
    }

    private fun startListenerService() {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, RemoteService::class.java),
        )
    }

    companion object {
        private const val MAX_COMPOSER_ATTACHMENTS = 8
        @Volatile private var instance: RemoteRepository? = null
        fun get(context: Context): RemoteRepository = instance ?: synchronized(this) {
            instance ?: RemoteRepository(context.applicationContext).also { instance = it }
        }
    }
}

private data class LocalAttachmentMetadata(val name: String, val mimeType: String, val size: Long)

private fun formatFileSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
    size >= 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "$size B"
}
