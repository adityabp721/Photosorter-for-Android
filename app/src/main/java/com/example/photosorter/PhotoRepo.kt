package com.example.photosorter

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
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

    /** Newest [limit] photos, excluding the app's own filed copies (they'd get re-filed). */
    fun recent(limit: Int): List<Photo> = query("DATE_ADDED DESC", limit, excludeOwnCopies = true)

    /** Everything in the library (capped). [excludeOwnCopies] skips Pictures/PhotoSorter/. */
    fun all(cap: Int = 200_000, excludeOwnCopies: Boolean = false): List<Photo> =
        query("DATE_ADDED DESC", cap, excludeOwnCopies)

    /** The photos with these ids that still exist. */
    fun byIds(ids: Collection<Long>): List<Photo> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(500).flatMap { chunk ->
            query(
                "DATE_ADDED DESC", chunk.size, false,
                "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})", null,
            )
        }
    }

    /** True if the MediaStore row for [photo] is still there. */
    fun exists(photo: Photo): Boolean =
        runCatching {
            resolver.query(photo.uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(true)

    // MediaStore rejects SQL keywords like LIMIT in the sort order on Android 14+,
    // so the cap is applied while iterating the cursor instead.
    @Suppress("DEPRECATION")
    private fun query(
        sort: String,
        maxRows: Int,
        excludeOwnCopies: Boolean,
        extraSel: String? = null,
        extraArgs: Array<String>? = null,
    ): List<Photo> {
        val sel = ArrayList<String>()
        val args = ArrayList<String>()
        if (excludeOwnCopies) {
            if (Build.VERSION.SDK_INT >= 29) {
                sel.add("${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?")
                args.add("${Organizer.BASE}/%")
            } else {
                sel.add("${MediaStore.MediaColumns.DATA} NOT LIKE ?")
                args.add("%/${Organizer.BASE}/%")
            }
        }
        if (extraSel != null) {
            sel.add(extraSel)
            extraArgs?.let { args.addAll(it) }
        }
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
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
            sel.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "($it)" },
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            sort
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
                // DATE_TAKEN is in milliseconds, DATE_ADDED in seconds
                val takenMs = c.getLong(iTaken)
                val dateMs = if (takenMs > 0) takenMs else c.getLong(iAdded) * 1000L
                out.add(
                    Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(iName) ?: "image_$id",
                        mime = c.getString(iMime) ?: "image/jpeg",
                        dateTakenMs = dateMs,
                        dateLabel = if (dateMs > 0) fmt.format(Date(dateMs)) else "unknown",
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
