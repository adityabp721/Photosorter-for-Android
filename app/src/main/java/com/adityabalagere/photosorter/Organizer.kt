package com.adityabalagere.photosorter

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DoneRec(
    val folder: String,
    val description: String,
    val outPath: String,
    val ts: Long,
)

object Organizer {
    const val BASE = "Pictures/PhotoSorter"
    private const val INDEX_FILE = "caption_index.json"
    private const val TAG = "Organizer"

    // ---- index (so re-runs don't spend API quota on photos we already filed) ----

    fun loadIndex(ctx: Context): MutableMap<Long, DoneRec> {
        val f = File(ctx.filesDir, INDEX_FILE)
        if (!f.exists()) return LinkedHashMap()
        return try {
            val o = JSONObject(f.readText())
            val out = LinkedHashMap<Long, DoneRec>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next().toLong()
                val r = o.getJSONObject(k.toString())
                out[k] = DoneRec(
                    r.getString("folder"),
                    r.getString("description"),
                    r.getString("outPath"),
                    r.optLong("ts"),
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "index unreadable, starting fresh", e)
            LinkedHashMap()
        }
    }

    private val saveLock = Any()

    /** Writes the index atomically (temp file + rename) so a crash mid-write can't corrupt it. */
    fun saveIndex(ctx: Context, index: Map<Long, DoneRec>): Unit = synchronized(saveLock) {
        try {
            val o = JSONObject()
            index.forEach { (k, v) ->
                o.put(
                    k.toString(),
                    JSONObject()
                        .put("folder", v.folder)
                        .put("description", v.description)
                        .put("outPath", v.outPath)
                        .put("ts", v.ts),
                )
            }
            val tmp = File(ctx.filesDir, "$INDEX_FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(ctx.filesDir, INDEX_FILE))) {
                File(ctx.filesDir, INDEX_FILE).writeText(o.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to save index", e)
        }
    }

    // ---- main operation: copy photo into a folder + attach the description ----

    fun copyAndDescribe(
        ctx: Context,
        resolver: ContentResolver,
        photo: Photo,
        ai: AiResult,
    ): DoneRec {
        val copy = copyInto(ctx, resolver, photo, ai.folder)
        writeSidecar(ctx, resolver, photo, ai.folder, copy.name, ai.description)
        setMediaDescription(resolver, copy.uri, ai.description)
        tryExifComment(ctx, resolver, copy.uri, copy.name, ai.description)
        val rec = DoneRec(ai.folder, ai.description, copy.path, System.currentTimeMillis())
        Log.i(TAG, "filed ${photo.title} -> ${copy.path} :: ${ai.description}")
        return rec
    }

    /** Free space on shared external storage in bytes, or -1 if unknown. */
    fun freeBytes(): Long {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Step 1 of a MOVE: checks that the copy in [rec] has the same size as the original. On a
     * mismatch the copy is removed and an error is thrown, so the original is never deleted
     * against a bad copy. The originals are deleted afterwards in one batch (Android 11+ needs
     * a single system confirmation for photos the app doesn't own).
     */
    fun verifyCopy(resolver: ContentResolver, photo: Photo, rec: DoneRec) {
        if (photo.sizeBytes <= 0) return
        val name = File(rec.outPath).name
        val copyUri = querySharedFile(resolver, rec.folder, name)
            ?: if (Build.VERSION.SDK_INT < 29) {
                val f = File(rec.outPath)
                if (f.length() == photo.sizeBytes) return
                f.delete()
                throw IllegalStateException("Copy verification failed (size mismatch) - original kept in place")
            } else throw IllegalStateException("Copied file not found - original kept in place")
        val copiedSize = resolver.query(
            copyUri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        if (copiedSize != photo.sizeBytes) {
            try { resolver.delete(copyUri, null, null) } catch (_: Exception) {}
            throw IllegalStateException("Copy verification failed (size mismatch) - original kept in place")
        }
    }

    /**
     * Copies a photo into an existing trip folder. No per-photo AI call - the sidecar and
     * description carry the trip-level [summary]. Used by the Trips tab.
     */
    fun copyToFolder(
        ctx: Context,
        resolver: ContentResolver,
        photo: Photo,
        folder: String,
        summary: String,
    ): DoneRec {
        val copy = copyInto(ctx, resolver, photo, folder)
        writeSidecar(ctx, resolver, photo, folder, copy.name, summary)
        setMediaDescription(resolver, copy.uri, summary)
        val rec = DoneRec(folder, summary, copy.path, System.currentTimeMillis())
        Log.i(TAG, "filed ${photo.title} -> ${copy.path} (trip)")
        return rec
    }

    /** A finished copy: its MediaStore uri (null before Android 10), final file name and path. */
    private class Copy(val uri: Uri?, val name: String, val path: String)

    private fun copyInto(ctx: Context, resolver: ContentResolver, photo: Photo, folder: String): Copy {
        val name = uniqueName(resolver, folder, photo.title)
        return if (Build.VERSION.SDK_INT >= 29) copyViaMediaStore(resolver, photo, folder, name)
        else copyViaFile(ctx, resolver, photo, folder, name)
    }

    private fun copyViaMediaStore(resolver: ContentResolver, photo: Photo, folder: String, name: String): Copy {
        val dir = "$BASE/$folder"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, photo.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // keep the original capture time so copies don't jump to "today" in the gallery
            if (photo.dateTakenMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTakenMs)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Cannot open output stream")
            out.use { o ->
                val input = resolver.openInputStream(photo.uri)
                    ?: throw IllegalStateException("Cannot open input stream")
                input.use { it.copyTo(o) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // MediaStore may have renamed the file if a parallel copy took the same name
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
            return Copy(uri, actual, "$dir/$actual")
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun copyViaFile(ctx: Context, resolver: ContentResolver, photo: Photo, folder: String, name: String): Copy {
        val sub = "PhotoSorter/$folder"
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), sub)
        dir.mkdirs()
        val f = File(dir, name)
        val input = resolver.openInputStream(photo.uri)
            ?: throw IllegalStateException("Cannot open input stream")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
        return Copy(null, name, f.absolutePath)
    }

    private fun uniqueName(resolver: ContentResolver, folder: String, title: String): String {
        val dot = title.lastIndexOf('.')
        val base = if (dot > 0) title.substring(0, dot) else title
        val ext = if (dot > 0) title.substring(dot) else ""
        var i = 0
        var candidate = title
        while (nameTaken(resolver, folder, candidate)) {
            i++
            candidate = "$base ($i)$ext"
        }
        return candidate
    }

    @Suppress("DEPRECATION")
    private fun nameTaken(resolver: ContentResolver, folder: String, name: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            val n = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                sel,
                arrayOf("$BASE/$folder/", name), // MediaStore keeps a trailing slash
                null,
            )?.use { it.count }
            n == null || n > 0
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhotoSorter/$folder/$name").exists()
        }
    }

    /** Writes '<name>.description.txt' into the same folder as the copy. */
    @Suppress("DEPRECATION")
    private fun writeSidecar(ctx: Context, resolver: ContentResolver, photo: Photo, folder: String, name: String, description: String) {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val sidecarName = "$stem.description.txt"
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
            .append("PhotoSorter - filed copy\n")
            .append("Folder: $folder\n")
            .append("Source: ${photo.uri}\n")
            .append("Taken: ${photo.dateLabel}\n")
        if (photo.latitude != null) sb.append("GPS: ${photo.latitude}, ${photo.longitude}\n")
        sb.append("\n")
            .append(description)
            .append("\n\n")
            .append("Filed on ").append(df.format(Date())).append("\n")
        val text = sb.toString()
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 11+ rejects non-media files in Pictures via MediaStore
            // (only Download/ are allowed), so this is best-effort - the description
            // is also stored in the copy's MediaStore "description" column instead.
            runCatching {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sidecarName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$BASE/$folder")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), cv)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
            }.onFailure { Log.w(TAG, "sidecar file skipped", it) }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhotoSorter/$folder")
            dir.mkdirs()
            File(dir, sidecarName).writeText(text)
            MediaScannerConnection.scanFile(ctx, arrayOf(dir.absolutePath), null, null)
        }
    }

    /** Best-effort: stores the description in the copy's MediaStore "description" column. */
    private fun setMediaDescription(resolver: ContentResolver, uri: Uri?, description: String) {
        if (Build.VERSION.SDK_INT < 29 || uri == null) return
        try {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.DESCRIPTION, description) },
                null, null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "description column skipped", e)
        }
    }

    /**
     * Best-effort: stores the description in the copy's EXIF UserComment tag.
     * Scoped storage blocks direct file paths, so the image round-trips through a
     * temp file and is written back via the content stream.
     */
    private fun tryExifComment(ctx: Context, resolver: ContentResolver, uri: Uri?, name: String, description: String) {
        if (uri == null) return
        val tmp = File(ctx.cacheDir, "exif_tmp_${System.nanoTime()}_$name")
        try {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return
            val exif = ExifInterface(tmp)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Unicode=$description")
            exif.saveAttributes()
            tmp.inputStream().use { input ->
                // "wt" truncates - plain "w" can leave stale trailing bytes on some devices
                resolver.openOutputStream(uri, "wt")?.use { input.copyTo(it) } ?: Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF comment skipped", e)
        } finally {
            tmp.delete()
        }
    }

    private fun querySharedFile(resolver: ContentResolver, folder: String, name: String): Uri? {
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            sel,
            arrayOf("$BASE/$folder/", name), // MediaStore keeps a trailing slash
            null,
        )?.use {
            if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)) else null
        }
    }

    // ---- open a filed copy from the UI ----

    fun openCopy(ctx: Context, outPath: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val name = File(outPath).name
            val dir = outPath.removeSuffix("/$name")
            val u = querySharedFile(ctx.contentResolver, dir.removePrefix("$BASE/"), name)
            if (u != null) {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure {
                    Toast.makeText(ctx, "No app found to open that copy", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(ctx, "Copy no longer found: $outPath", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(ctx, "Copy at: $outPath", Toast.LENGTH_SHORT).show()
        }
    }
}
