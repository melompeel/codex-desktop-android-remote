package com.alphapi.codexremote

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class TaskMediaCache(
    private val directory: File,
    private val maxBytes: Long = 128L * 1_024 * 1_024,
    parallelism: Int = 2,
) {
    private val downloads = Semaphore(parallelism)

    suspend fun load(key: String, download: suspend (File) -> Unit): File = downloads.withPermit {
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cached = File(directory, "$name.image")
        if (cached.isFile && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())
            return@withPermit cached
        }
        val pending = File.createTempFile("download-", ".part", directory)
        try {
            download(pending)
            check(pending.length() in 1..maxBytes) { "image-cache-limit-exceeded" }
            synchronized(this) {
                if (cached.isFile) cached.delete()
                check(pending.renameTo(cached)) { "image-cache-write-failed" }
                trim(cached)
            }
            cached
        } finally {
            pending.delete()
        }
    }

    private fun trim(keep: File) {
        val files = directory.listFiles { file -> file.extension == "image" }.orEmpty()
        var size = files.sumOf(File::length)
        for (file in files.sortedBy(File::lastModified)) {
            if (size <= maxBytes) break
            if (file == keep) continue
            val length = file.length()
            if (file.delete()) size -= length
        }
    }
}
