package com.alphapi.codexremote

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskImageDecoderTest {
    @Test
    fun largeDesktopImageUsesBoundedBitmapMemory() {
        val source = Bitmap.createBitmap(4_096, 2_048, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile(
            "large-preview-",
            ".png",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        )
        file.outputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        source.recycle()

        val decoded = decodeTaskImage(file)
        try {
            assertNotNull(decoded)
            assertTrue("desktop images must fit the mobile preview budget", decoded!!.width <= 2_048)
            assertTrue("a preview must not retain more than 16 MiB", decoded.allocationByteCount <= 16 * 1_024 * 1_024)
        } finally {
            decoded?.recycle()
            file.delete()
        }
    }
}
