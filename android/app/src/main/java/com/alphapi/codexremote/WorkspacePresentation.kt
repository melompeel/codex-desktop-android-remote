package com.alphapi.codexremote

import java.util.Locale

internal data class ProjectGroup(
    val key: String,
    val name: String,
    val cwd: String?,
    val tasks: List<TaskDto>,
) {
    companion object {
        const val ALL_KEY = "__all__"
        const val UNASSIGNED_KEY = "__unassigned__"
    }
}

internal fun groupTasksByProject(tasks: List<TaskDto>): List<ProjectGroup> {
    val assigned = linkedMapOf<String, MutableList<TaskDto>>()
    val displayPaths = linkedMapOf<String, String>()
    val displayLabels = linkedMapOf<String, String>()
    val unassigned = mutableListOf<TaskDto>()

    tasks.forEach { task ->
        val path = task.cwd?.trim()?.takeIf(String::isNotEmpty)
        if (path == null) {
            unassigned += task
        } else {
            val key = task.cwdGroupKey ?: normalizeProjectKey(path)
            displayPaths.putIfAbsent(key, path.trimEnd('/', '\\'))
            task.cwdGroupLabel?.let { displayLabels.putIfAbsent(key, it) }
            assigned.getOrPut(key) { mutableListOf() } += task
        }
    }

    val groups = assigned.map { (key, groupTasks) ->
        val cwd = displayPaths.getValue(key)
        ProjectGroup(
            key = key,
            name = displayLabels[key] ?: projectName(cwd),
            cwd = cwd,
            tasks = sortProjectTasks(groupTasks),
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    return if (unassigned.isEmpty()) groups else groups + ProjectGroup(
        key = ProjectGroup.UNASSIGNED_KEY,
        name = "未归属",
        cwd = null,
        tasks = sortProjectTasks(unassigned),
    )
}

internal fun normalizeProjectKey(cwd: String): String = cwd
    .trim()
    .replace('\\', '/')
    .trimEnd('/')
    .lowercase(Locale.ROOT)

private fun projectName(cwd: String): String = cwd
    .replace('\\', '/')
    .trimEnd('/')
    .substringAfterLast('/')
    .ifBlank { cwd }

private fun sortProjectTasks(tasks: List<TaskDto>): List<TaskDto> = tasks.sortedWith(
    compareByDescending<TaskDto> { it.ownerAvailable }
        .thenByDescending { it.status == "active" || it.status == "inProgress" }
        .thenByDescending { it.updatedAt ?: 0L }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
)

internal fun compatibleReasoningEffort(model: ModelOptionDto, requested: String?): String? {
    val supported = model.supportedReasoningEfforts.map { it.reasoningEffort }
    if (supported.isEmpty()) return null
    return requested?.takeIf(supported::contains)
        ?: model.defaultReasoningEffort?.takeIf(supported::contains)
        ?: supported.first()
}

internal fun defaultDeliveryFor(status: String): DeliveryMode = when (status) {
    "active", "inProgress" -> DeliveryMode.STEER
    else -> DeliveryMode.START
}
