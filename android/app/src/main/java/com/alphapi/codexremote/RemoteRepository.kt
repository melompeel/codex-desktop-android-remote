package com.alphapi.codexremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket

class RemoteRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val store = CredentialStore(applicationContext)
    private val pendingReviewStore = PendingReviewStore(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = mutableState.asStateFlow()
    @Volatile private var api: BridgeApi? = null
    private var stream: WebSocket? = null
    private val reconnectScheduler = ReconnectScheduler(scope) {
        if (api != null) {
            openStream()
            runCatching { refreshNow() }.onFailure(::recordError)
        }
    }
    private var refreshCoordinator: ConflatedRefreshCoordinator? = null
    private var restoreJob: Job? = null
    @Volatile private var streamGeneration = 0
    private var followedThreadId: String? = null
    private var mediaCacheKey = ""
    private val mediaCache = TaskMediaCache(File(applicationContext.cacheDir, "task_media"))
    private val mediaJobs = mutableMapOf<String, Job>()

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

    fun pair(serverUrl: String, code: String, deviceName: String, connectionName: String = "") {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val normalized = BridgeEndpoint.normalize(serverUrl)
                val response = BridgeApi(normalized, null).pair(code.trim(), deviceName.trim())
                val label = connectionName.trim()
                val saved = if (label.isBlank()) {
                    StoredConnection(normalized, response.deviceId, response.token)
                } else {
                    StoredConnection(normalized, response.deviceId, response.token, label)
                }
                pendingReviewStore.clear()
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

    fun refreshModels() {
        val bridge = api ?: return
        scope.launch {
            runCatching { bridge.models(refresh = true) }
                .onSuccess { models -> if (api === bridge) update { it.copy(models = models) } }
                .onFailure(::recordError)
        }
    }

    fun addServerUrl(name: String, serverUrl: String) {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val normalized = BridgeEndpoint.normalize(serverUrl)
                val saved = requireNotNull(store.addServerUrl(name, normalized)) { "当前没有可复用的配对凭据" }
                activateConnection(saved)
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun switchServerUrl(serverUrl: String) {
        if (serverUrl == mutableState.value.serverUrl) return
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val saved = requireNotNull(store.selectServerUrl(serverUrl)) { "找不到已保存的连接地址" }
                activateConnection(saved)
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun removeServerUrl(serverUrl: String) {
        val snapshot = mutableState.value
        if (snapshot.serverAddresses.size <= 1) {
            update { it.copy(error = "至少需要保留一个连接地址") }
            return
        }
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val saved = requireNotNull(store.removeServerUrl(serverUrl)) { "当前没有已保存的连接" }
                if (saved.serverUrl != snapshot.serverUrl) activateConnection(saved)
                else update { it.copy(serverAddresses = store.serverAddresses()) }
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun select(threadId: String) {
        cancelMediaLoads()
        markTaskViewed(threadId)
        update {
            it.copy(
                selectedThreadId = threadId,
                taskDetail = null,
                taskMediaById = emptyMap(),
                loadingTaskMediaIds = emptySet(),
                failedTaskMediaIds = emptySet(),
                workspaceFiles = emptyList(),
                workspaceFilesLoading = false,
            )
        }
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

    fun markTaskViewed(threadId: String) {
        val remaining = pendingReviewStore.markViewed(threadId)
        update { it.copy(completedReviewThreadIds = remaining) }
    }

    fun openTaskResource(threadId: String, resource: TimelineResourceDto) {
        if (resource.resourceId in mutableState.value.downloadingResourceIds) return
        scope.launch {
            update {
                it.copy(
                    downloadingResourceIds = it.downloadingResourceIds + resource.resourceId,
                    error = null,
                )
            }
            runCatching {
                val bytes = requireApi().taskResource(threadId, resource.resourceId)
                val directory = File(applicationContext.cacheDir, "remote_files").apply { mkdirs() }
                val safeName = resource.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    .ifBlank { "download" }
                val file = File(directory, "${resource.resourceId.take(12)}-$safeName")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.files",
                    file,
                )
                val viewIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, resource.mimeType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                applicationContext.startActivity(viewIntent)
            }.onFailure(::recordError)
            update {
                it.copy(downloadingResourceIds = it.downloadingResourceIds - resource.resourceId)
            }
        }
    }

    fun loadWorkspaceFiles(threadId: String, query: String = "") {
        scope.launch {
            update { it.copy(workspaceFilesLoading = true, error = null) }
            runCatching { requireApi().workspaceFiles(threadId, query) }
                .onSuccess { files -> update { it.copy(workspaceFiles = files) } }
                .onFailure(::recordError)
            update { it.copy(workspaceFilesLoading = false) }
        }
    }

    fun addWorkspaceAttachment(threadId: String, file: WorkspaceFileDto) {
        val existing = mutableState.value.attachmentsByThread[threadId].orEmpty()
        if (existing.size >= MAX_COMPOSER_ATTACHMENTS) {
            update { it.copy(error = "每条消息最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件") }
            return
        }
        val pending = ComposerAttachment(
            localId = UUID.randomUUID().toString(),
            uri = "",
            name = file.name,
            mimeType = file.mimeType,
            size = file.size,
            uploadState = AttachmentUploadState.UPLOADING,
        )
        update { state ->
            state.copy(
                attachmentsByThread = state.attachmentsByThread +
                    (threadId to (state.attachmentsByThread[threadId].orEmpty() + pending)),
                error = null,
            )
        }
        scope.launch {
            runCatching { requireApi().importWorkspaceAttachment(threadId, file.relativePath) }
                .onSuccess { uploaded ->
                    replaceAttachment(threadId, pending.localId) {
                        it.copy(
                            uploadState = AttachmentUploadState.READY,
                            attachmentId = uploaded.attachmentId,
                        )
                    }
                }
                .onFailure { error ->
                    replaceAttachment(threadId, pending.localId) {
                        it.copy(
                            uploadState = AttachmentUploadState.FAILED,
                            error = error.message ?: "电脑文件读取失败",
                        )
                    }
                }
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
            update { it.copy(taskCreationError = "当前连接暂不支持远程新建任务") }
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
        streamGeneration += 1
        cancelMediaLoads()
        stream?.cancel()
        stream = null
        reconnectScheduler.reset()
        refreshCoordinator?.dispose()
        refreshCoordinator = null
        restoreJob?.cancel()
        restoreJob = null
        followedThreadId = null
        api = null
        store.clear()
        pendingReviewStore.clear()
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
        mediaCacheKey = saved.deviceId
        update {
            it.copy(
                configured = true,
                serverUrl = saved.serverUrl,
                serverAddresses = store.serverAddresses(),
                error = null,
            )
        }
    }

    private suspend fun activateConnection(saved: StoredConnection) {
        streamGeneration += 1
        cancelMediaLoads()
        stream?.cancel()
        stream = null
        reconnectScheduler.reset()
        refreshCoordinator?.dispose()
        refreshCoordinator = null
        followedThreadId = null
        api = null
        configure(saved)
        openStream()
        refreshNow()
    }

    private fun openStream() {
        val bridge = api ?: return
        reconnectScheduler.cancel()
        val generation = ++streamGeneration
        stream?.cancel()
        update { it.copy(connected = false) }
        stream = bridge.stream(
            onEvent = {
                if (generation == streamGeneration) scheduleRefresh(150)
            },
            onConnected = { connected ->
                if (generation != streamGeneration) return@stream
                update { it.copy(connected = connected) }
                if (connected) {
                    reconnectScheduler.reset()
                    scheduleRefresh(0)
                } else {
                    followedThreadId = null
                    reconnectScheduler.schedule()
                }
            },
        )
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
        if (api !== bridge) return
        val completedReviewThreadIds = pendingReviewStore.observe(tasks)
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
        val visibleMediaIds = detail?.items?.mapNotNull { it.media?.mediaId }?.toSet().orEmpty()
        if (api !== bridge) return
        update { current ->
            current.copy(
                configured = true,
                tasks = tasks,
                taskDetail = detail,
                approvals = approvals,
                completedReviewThreadIds = completedReviewThreadIds,
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
                taskMediaById = current.taskMediaById.filterKeys { it in visibleMediaIds },
                loadingTaskMediaIds = current.loadingTaskMediaIds.filterTo(mutableSetOf()) {
                    it in visibleMediaIds
                },
                failedTaskMediaIds = current.failedTaskMediaIds.filterTo(mutableSetOf()) {
                    it in visibleMediaIds
                },
                error = null,
            )
        }
    }

    fun loadTaskMedia(threadId: String, mediaId: String) {
        val snapshot = mutableState.value
        if (snapshot.selectedThreadId != threadId || snapshot.taskMediaById[mediaId]?.isFile == true) return
        val bridge = api ?: return
        val cacheKey = "$mediaCacheKey:$threadId:$mediaId"
        synchronized(mediaJobs) {
            if (mediaJobs[mediaId]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                update {
                    it.copy(
                        loadingTaskMediaIds = it.loadingTaskMediaIds + mediaId,
                        failedTaskMediaIds = it.failedTaskMediaIds - mediaId,
                    )
                }
                try {
                    val file = mediaCache.load(cacheKey) { bridge.taskMedia(threadId, mediaId, it) }
                    if (api === bridge) {
                        update { state ->
                            val stillVisible = state.selectedThreadId == threadId &&
                                state.taskDetail?.items?.any {
                                    it.media?.mediaId == mediaId
                                } == true
                            state.copy(
                                taskMediaById = if (stillVisible) {
                                    state.taskMediaById + (mediaId to file)
                                } else state.taskMediaById,
                                failedTaskMediaIds = state.failedTaskMediaIds - mediaId,
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (api === bridge && mutableState.value.selectedThreadId == threadId) {
                        update { it.copy(failedTaskMediaIds = it.failedTaskMediaIds + mediaId) }
                    }
                } finally {
                    synchronized(mediaJobs) {
                        if (mediaJobs[mediaId] == coroutineContext[Job]) {
                            mediaJobs.remove(mediaId)
                            update { it.copy(loadingTaskMediaIds = it.loadingTaskMediaIds - mediaId) }
                        }
                    }
                }
            }
            mediaJobs[mediaId] = job
            job.start()
        }
    }

    private fun cancelMediaLoads() {
        synchronized(mediaJobs) {
            mediaJobs.values.forEach(Job::cancel)
            mediaJobs.clear()
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
    private fun update(block: (RemoteState) -> RemoteState) { mutableState.update(block) }

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
