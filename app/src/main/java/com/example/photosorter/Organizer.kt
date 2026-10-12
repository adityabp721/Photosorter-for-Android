package com.example.photosorter

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

    fun saveIndex(ctx: Context, index: Map<Long, DoneRec>) {
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
            File(ctx.filesDir, INDEX_FILE).writeText(o.toString(1))
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
        val name = uniqueName(resolver, ai.folder, photo.title)
        val outPath = if (Build.VERSION.SDK_INT >= 29) {
            copyViaMediaStore(resolver, photo, ai.folder, name)
        } else {
            copyViaFile(ctx, resolver, photo, ai.folder, name)
        }
        writeSidecar(ctx, resolver, photo, ai.folder, name, ai.description)
        setMediaDescription(resolver, ai.folder, name, ai.description)
        tryExifComment(ctx, resolver, ai.folder, name, ai.description)
        val rec = DoneRec(ai.folder, ai.description, outPath, System.currentTimeMillis())
        Log.i(TAG, "filed ${photo.title} -> $outPath :: ${ai.description}")
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
     * MOVES a photo into [folder]: copies it there, verifies the copy's size, then deletes the
     * original. If verification or deletion fails, the original is left in place and an error is
     * thrown (a failed delete can never lose data - worst case a duplicate remains).
     */
    fun moveToFolder(
        ctx: Context,
        resolver: ContentResolver,
        photo: Photo,
        folder: String,
        summary: String,
    ): DoneRec {
        val rec = copyToFolder(ctx, resolver, photo, folder, summary)
        if (photo.sizeBytes > 0) {
            val copyUri = querySharedFile(resolver, folder, File(rec.outPath).name)
                ?: throw IllegalStateException("Copied file not found after move - original kept in place")
            val copiedSize = resolver.query(
                copyUri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
            if (copiedSize != photo.sizeBytes) {
                try { resolver.delete(copyUri, null, null) } catch (_: Exception) {}
                throw IllegalStateException("Copy verification failed (size mismatch) - original kept in place")
            }
        }
        val deleted = resolver.delete(photo.uri, null, null)
        if (deleted <= 0) {
            throw IllegalStateException(
                "Copied to ${rec.outPath} but the original could not be deleted - remove it manually in your Photos app"
            )
        }
        Log.i(TAG, "moved ${photo.title} -> ${rec.outPath}")
        return rec
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
        val name = uniqueName(resolver, folder, photo.title)
        val outPath = if (Build.VERSION.SDK_INT >= 29) {
            copyViaMediaStore(resolver, photo, folder, name)
        } else {
            copyViaFile(ctx, resolver, photo, folder, name)
        }
        writeSidecar(ctx, resolver, photo, folder, name, summary)
        setMediaDescription(resolver, folder, name, summary)
        val rec = DoneRec(folder, summary, outPath, System.currentTimeMillis())
        Log.i(TAG, "filed ${photo.title} -> $outPath (trip)")
        return rec
    }

    private fun copyViaMediaStore(resolver: ContentResolver, photo: Photo, folder: String, name: String): String {
        val dir = "$BASE/$folder"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, photo.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
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
            return "$dir/$name"
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun copyViaFile(ctx: Context, resolver: ContentResolver, photo: Photo, folder: String, name: String): String {
        val sub = "PhotoSorter/$folder"
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), sub)
        dir.mkdirs()
        val f = File(dir, name)
        val input = resolver.openInputStream(photo.uri)
            ?: throw IllegalStateException("Cannot open input stream")
        input.use { it.copyTo(f.outputStream()) }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
        return f.absolutePath
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
                arrayOf("$BASE/$folder", name),
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
    private fun setMediaDescription(resolver: ContentResolver, folder: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val uri = querySharedFile(resolver, folder, name) ?: return
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
    private fun tryExifComment(ctx: Context, resolver: ContentResolver, folder: String, name: String, description: String) {
        val tmp = File(ctx.cacheDir, "exif_tmp_$name")
        try {
            val uri = querySharedFile(resolver, folder, name) ?: return
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return
            val exif = ExifInterface(tmp)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Unicode=$description")
            exif.saveAttributes()
            tmp.inputStream().use { input ->
                resolver.openOutputStream(uri)?.use { input.copyTo(it) } ?: Unit
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
            arrayOf("$BASE/$folder", name),
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
