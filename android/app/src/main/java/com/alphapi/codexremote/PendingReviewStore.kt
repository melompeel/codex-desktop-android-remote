package com.alphapi.codexremote

import android.content.Context
import org.json.JSONObject

class PendingReviewStore(context: Context) {
    private val preferences = context.getSharedPreferences("pending_task_reviews", Context.MODE_PRIVATE)

    @Synchronized
    fun observe(tasks: List<TaskDto>): Set<String> {
        val previous = readObservations()
        val pending = preferences.getStringSet("pending_threads", emptySet()).orEmpty().toMutableSet()
        val next = JSONObject()
        pending.retainAll(tasks.mapTo(mutableSetOf()) { it.threadId })

        for (task in tasks) {
            val old = previous.optJSONObject(task.threadId)
            val oldStatus = old?.optString("status")?.takeIf(String::isNotBlank)
            val oldUpdatedAt = old?.takeIf { it.has("updatedAt") }?.optLong("updatedAt")
            if (shouldMarkCompleted(oldStatus, oldUpdatedAt, task.status, task.updatedAt)) {
                pending += task.threadId
            }
            next.put(
                task.threadId,
                JSONObject().apply {
                    put("status", task.status)
                    task.updatedAt?.let { put("updatedAt", it) }
                },
            )
        }

        preferences.edit()
            .putString("observations", next.toString())
            .putStringSet("pending_threads", pending)
            .apply()
        return pending
    }

    @Synchronized
    fun markViewed(threadId: String): Set<String> {
        val pending = preferences.getStringSet("pending_threads", emptySet()).orEmpty().toMutableSet()
        pending -= threadId
        preferences.edit().putStringSet("pending_threads", pending).apply()
        return pending
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun readObservations(): JSONObject = runCatching {
        JSONObject(preferences.getString("observations", "{}") ?: "{}")
    }.getOrDefault(JSONObject())
}

internal fun shouldMarkCompleted(
    previousStatus: String?,
    previousUpdatedAt: Long?,
    currentStatus: String,
    currentUpdatedAt: Long?,
): Boolean {
    if (previousStatus == null || isActiveTaskStatus(currentStatus)) return false
    if (isActiveTaskStatus(previousStatus)) return true
    return previousUpdatedAt != null && currentUpdatedAt != null && currentUpdatedAt > previousUpdatedAt
}

internal fun isActiveTaskStatus(status: String): Boolean =
    status.lowercase().replace("-", "").replace("_", "") in
        setOf("active", "inprogress", "running")
