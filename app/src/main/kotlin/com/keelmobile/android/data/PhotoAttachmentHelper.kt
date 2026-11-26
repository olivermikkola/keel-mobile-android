package com.keelmobile.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.keelmobile.harness.VisionImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes and scales an attached or camera-captured Android photo to <= 768px on its
 * longest edge (with inSampleSize downsampling and EXIF rotation) and converts to
 * tightly packed RGB24 bytes for native mtmd vision input.
 */
object PhotoAttachmentHelper {
    const val MAX_EDGE_PX = 768
    const val MAX_PHOTOS_PER_TURN = 3

    fun decodeToVisionImage(context: Context, uri: Uri): VisionImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val oriented = applyExifOrientation(rawBitmap, orientation)
        if (oriented !== rawBitmap) rawBitmap.recycle()

        val scaled = scaleDownIfNeeded(oriented, MAX_EDGE_PX)
        if (scaled !== oriented) oriented.recycle()

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

    private fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
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
