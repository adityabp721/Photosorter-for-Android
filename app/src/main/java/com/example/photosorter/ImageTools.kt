package com.example.photosorter

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

object ImageTools {

    /**
     * Reads the image at [uri], downscales it to at most ~512px on the long edge
     * and returns it as base64 JPEG (small enough to keep API costs low).
     */
    fun toBase64Jpeg(resolver: ContentResolver, uri: Uri): String =
        Base64.encodeToString(toJpegBytes(resolver, uri, 512), Base64.NO_WRAP)

    /** Reads the image at [uri], downscales it (long edge <= [maxPx]) and returns JPEG bytes. */
    fun toJpegBytes(resolver: ContentResolver, uri: Uri, maxPx: Int = 768): ByteArray {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            throw IllegalArgumentException("Cannot decode image (unsupported or corrupt format)")
        }
        var sample = 1
        while (probe.outWidth / (sample * 2) >= maxPx && probe.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Failed to decode image")
        try {
            val bytes = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
            return bytes.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Decodes with EXIF rotation applied, long edge at most [maxPx].
     * Used by the on-device labeler and the near-duplicate hash (both want a small bitmap).
     */
    fun decodeSmall(resolver: ContentResolver, uri: Uri, maxPx: Int = 256): Bitmap {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            throw IllegalArgumentException("Cannot decode image")
        }
        var scale = 1
        while (maxOf(probe.outWidth, probe.outHeight) / (scale * 2) >= maxPx) scale *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = scale }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Failed to decode image")
        val rotation = rotationFor(resolver, uri)
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            bmp = rotated
        }
        val w = bmp.width
        val h = bmp.height
        if (maxOf(w, h) > maxPx) {
            val f = maxPx.toFloat() / maxOf(w, h).toFloat()
            val small = Bitmap.createScaledBitmap(
                bmp, (w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1), true
            )
            if (small !== bmp) bmp.recycle()
            bmp = small
        }
        return bmp
    }

    /**
     * Reads GPS from the photo's EXIF (MediaStore has no reliable lat/lon columns for images).
     * Returns [latitude, longitude] or null when absent/unreadable.
     */
    fun gpsFor(resolver: ContentResolver, uri: Uri): DoubleArray? {
        return try {
            val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return null
            var lat = parseGpsRationals(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)) ?: return null
            var lon = parseGpsRationals(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)) ?: return null
            if (exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) == "S") lat = -lat
            if (exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) == "W") lon = -lon
            doubleArrayOf(lat, lon)
        } catch (e: Exception) {
            null
        }
    }

    /** Parses an EXIF GPS value like "40/1,26/1,2939/100" into decimal degrees. */
    private fun parseGpsRationals(raw: String?): Double? {
        if (raw == null) return null
        val parts = raw.split(",").map { it.trim() }
        if (parts.size < 3 || parts.any { it.isEmpty() }) return null
        val vals = ArrayList<Double>(3)
        for (p in parts.take(3)) {
            val bits = p.split("/")
            val num = bits[0].toDoubleOrNull() ?: return null
            val den = if (bits.size > 1) bits[1].toDoubleOrNull() ?: return null else 1.0
            if (den == 0.0) return null
            vals.add(num / den)
        }
        return vals[0] + vals[1] / 60.0 + vals[2] / 3600.0
    }

    private fun rotationFor(resolver: ContentResolver, uri: Uri): Int {
        try {
            val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return 0
            val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            return when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            return 0
        }
    }
}
