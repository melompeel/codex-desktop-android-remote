package com.alphapi.codexremote

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TaskMediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun loadsAtMostTwoImagesAtOnceAndKeepsResultsOnDisk() = runTest {
        val release = CompletableDeferred<Unit>()
        val cache = TaskMediaCache(temporary.root)
        var active = 0
        var maxActive = 0
        val images = (1..12).map { id ->
            async {
                cache.load("image-$id") { file ->
                    active++
                    maxActive = maxOf(active, maxActive)
                    release.await()
                    file.writeText("image-$id")
                    active--
                }
            }
        }
        runCurrent()
        assertEquals(2, active)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, maxActive)
        assertTrue(images.awaitAll().all(File::isFile))
        cache.load("image-1") { throw AssertionError("cached image must not download again") }
    }

    @Test
    fun boundsDiskUsageAndRemovesPartialDownloadsAfterFailure() = runTest {
        val cache = TaskMediaCache(temporary.root, maxBytes = 16)
        val oldest = cache.load("first") { it.writeBytes(ByteArray(10)) }
        oldest.setLastModified(1)
        val latest = cache.load("second") { it.writeBytes(ByteArray(10)) }
        assertFalse(oldest.exists())
        assertTrue(latest.exists())
        assertTrue(temporary.root.listFiles()!!.sumOf(File::length) <= 16)

        val failed = runCatching {
            cache.load("failed") {
                it.writeText("partial")
                throw IOException("test-network-interrupted")
            }
        }
        assertTrue(failed.isFailure)
        assertFalse(temporary.root.listFiles()!!.any { it.extension == "part" })
    }

    @Test
    fun cancellationDropsIncompleteFileAndAllowsTheNextTaskImage() = runTest {
        val cache = TaskMediaCache(temporary.root, parallelism = 1)
        val suspended = CompletableDeferred<Unit>()
        val first = launch {
            cache.load("old-task") {
                it.writeText("partial")
                suspended.await()
            }
        }
        runCurrent()
        first.cancel()
        runCurrent()

        val next = cache.load("new-task") { it.writeText("image") }
        assertEquals("image", next.readText())
        assertFalse(temporary.root.listFiles()!!.any { it.extension == "part" })
    }
}
