package com.keelmobile.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.keelmobile.harness.VisionImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes and scales an attached or camera-captured Android photo to <= 768px on its
 * longest edge and converts to tightly packed RGB24 bytes for native mtmd vision input.
 */
object PhotoAttachmentHelper {
    const val MAX_EDGE_PX = 768
    const val MAX_PHOTOS_PER_TURN = 3

    fun decodeToVisionImage(context: Context, uri: Uri): VisionImage? {
        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        val scaled = scaleDownIfNeeded(rawBitmap, MAX_EDGE_PX)
        if (scaled !== rawBitmap) rawBitmap.recycle()

        val w = scaled.width
        val h = scaled.height
        val argb = IntArray(w * h)
        scaled.getPixels(argb, 0, w, 0, 0, w, h)
        scaled.recycle()

        val rgb = ByteArray(w * h * 3)
        var dst = 0
        for (px in argb) {
            rgb[dst++] = ((px shr 16) and 0xFF).toByte()
            rgb[dst++] = ((px shr 8) and 0xFF).toByte()
            rgb[dst++] = (px and 0xFF).toByte()
        }
        return VisionImage(w, h, rgb)
    }

    private fun scaleDownIfNeeded(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest.toFloat()
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
