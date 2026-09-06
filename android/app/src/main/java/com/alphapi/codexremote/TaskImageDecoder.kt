package com.alphapi.codexremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

internal fun decodeTaskImage(file: File): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inSampleSize = 1
    }
    BitmapFactory.decodeFile(file.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val longest = maxOf(options.outWidth, options.outHeight)
    while ((longest.toLong() + options.inSampleSize - 1) / options.inSampleSize > 2_048) {
        options.inSampleSize *= 2
    }
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
