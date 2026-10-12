package com.example.photosorter

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Photo(
    val id: Long,
    val uri: Uri,
    val title: String,
    val mime: String,
    val dateTakenMs: Long,
    val dateLabel: String,
    val width: Int,
    val height: Int,
    val latitude: Double?,
    val longitude: Double?,
    val sizeBytes: Long,
)

class PhotoRepo(private val resolver: ContentResolver) {

    fun recent(limit: Int): List<Photo> = query("DATE_ADDED DESC", limit)

    /** Everything in the library (capped). Used by the duplicate scanner. */
    fun all(cap: Int = 20000): List<Photo> = query("DATE_ADDED DESC", cap)

    // MediaStore rejects SQL keywords like LIMIT in the sort order on Android 14+,
    // so the cap is applied while iterating the cursor instead.
    private fun query(sort: String, maxRows: Int): List<Photo> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )
        val out = ArrayList<Photo>()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, sort
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (c.moveToNext() && out.size < maxRows) {
                val id = c.getLong(iId)
                val takenSec = c.getLong(iTaken)
                val dateSec = if (takenSec <= 0) c.getLong(iAdded) else takenSec
                out.add(
                    Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(iName) ?: "image_$id",
                        mime = c.getString(iMime) ?: "image/jpeg",
                        dateTakenMs = dateSec * 1000L,
                        dateLabel = if (dateSec > 0) fmt.format(Date(dateSec * 1000L)) else "unknown",
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        latitude = null,
                        longitude = null,
                        sizeBytes = c.getLong(iSize),
                    )
                )
            }
        }
        return out
    }
}
