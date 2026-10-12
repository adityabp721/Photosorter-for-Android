package com.example.photosorter

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

/**
 * A group of photos that are copies of the same shot.
 * `exact` = byte-identical (same SHA-256). Otherwise = same perceptual hash
 * (typical for re-saved / re-cropped / edited-then-saved versions).
 * photos are ordered newest first; the first one is the suggested "keep".
 */
data class DupCluster(val exact: Boolean, val photos: List<Photo>) {
    val key: Long get() = photos.first().id
    /** bytes reclaimable if we keep only the first (newest) photo */
    val reclaimableBytes: Long get() = photos.drop(1).sumOf { it.sizeBytes }
}

object DupHash {

    const val NEAR_HAMMING = 8   // bits out of 64 allowed to differ and still be "same photo"
    private const val N = 32

    fun sha256(resolver: ContentResolver, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var n = input.read(buf)
            while (n >= 0) {
                if (n > 0) md.update(buf, 0, n)
                n = input.read(buf)
            }
        } ?: throw IllegalStateException("Cannot open file")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 64-bit DCT-based perceptual hash (aHash/pHash family), DC removed, median threshold. */
    fun perceptualHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, N, N, true)
        try {
            val s = FloatArray(N * N)
            for (y in 0 until N) {
                for (x in 0 until N) {
                    val px = small.getPixel(x, y)
                    s[y * N + x] = 0.299f * (px shr 16 and 0xff) +
                        0.587f * (px shr 8 and 0xff) +
                        0.114f * (px and 0xff)
                }
            }
            val f = dct2(s)
            val low = ArrayList<Float>(63)
            for (y in 0..7) for (x in 0..7) {
                if (y == 0 && x == 0) continue
                low.add(f[y * N + x])
            }
            val median = low.sorted()[low.size / 2].toDouble()
            var bits = 0L
            for (y in 0..7) for (x in 0..7) {
                if (y == 0 && x == 0) continue
                bits = (bits shl 1) or (if (f[y * N + x] > median) 1L else 0L)
            }
            return bits
        } finally {
            if (small !== src) small.recycle()
        }
    }

    fun hamming(a: Long, b: Long): Int {
        var x = a xor b
        var c = 0
        while (x != 0L) {
            x = x and (x - 1)
            c++
        }
        return c
    }

    private val COS: Array<FloatArray> by lazy {
        Array(N) { coef -> FloatArray(N) { i ->
            val c = Math.cos(Math.PI * (2 * coef + 1) * i / N)
            if (coef == 0) (c / Math.sqrt(2.0)).toFloat() else c.toFloat()
        } }
    }

    private fun dct2(s: FloatArray): FloatArray {
        val m = FloatArray(N * N)
        for (i in 0 until N) {
            for (k in 0 until N) {
                var sum = 0f
                for (j in 0 until N) sum += COS[k][j] * s[i * N + j]
                m[i * N + k] = sum
            }
        }
        val f = FloatArray(N * N)
        for (k in 0 until N) {
            for (i in 0 until N) {
                var sum = 0f
                for (j in 0 until N) sum += COS[i][j] * m[j * N + k]
                f[i * N + k] = sum
            }
        }
        return f
    }
}

fun humanBytes(b: Long): String = when {
    b <= 0 -> "size unknown"
    b < 1 shl 20 -> "${b / 1024} KB"
    b < (1L shl 30) -> String.format(Locale.US, "%.1f MB", b.toDouble() / (1 shl 20))
    else -> String.format(Locale.US, "%.2f GB", b.toDouble() / (1L shl 30))
}

/**
 * Full-library duplicate scan: exact SHA-256 first, then perceptual hash for the rest.
 * [progress] is suspend and may hop threads - AppModel uses it to update the UI.
 */
suspend fun findDuplicates(
    resolver: ContentResolver,
    allPhotos: List<Photo>,
    progress: suspend (done: Int, total: Int, phase: String) -> Unit,
): List<DupCluster> = withContext(Dispatchers.IO) {
    val photos = allPhotos.filter { it.mime.lowercase().startsWith("image/") }.take(20000)

    // ---- pass 1: byte-identical ----
    val byHash = LinkedHashMap<String, MutableList<Photo>>()
    for ((i, p) in photos.withIndex()) {
        val h = try {
            DupHash.sha256(resolver, p.uri)
        } catch (e: Exception) {
            Log.w("DupHash", "hash failed", e); null
        }
        if (h != null) {
            val list = byHash.getOrPut(h) { ArrayList() }
            if (list.none { it.id == p.id }) list.add(p)
        }
        if (i % 25 == 0) progress(i, photos.size, "hashing")
    }
    progress(photos.size, photos.size, "hashing")

    val exactClusters = byHash.values.filter { it.size >= 2 }.map { DupCluster(exact = true, photos = it.toSortedDescendingByDate()) }
    val inExact = exactClusters.flatMap { it.photos }.mapTo(HashSet()) { it.id }

    // ---- pass 2: perceptual (re-saves, crops, mild edits) ----
    val candidates = photos.filter { it.id !in inExact }
    val leaders = ArrayList<Pair<Long, Int>>()      // (hash, index into nearClusters)
    val near = ArrayList<MutableList<Photo>>()
    for ((i, p) in candidates.withIndex()) {
        val h: Long = try {
            val bmp = ImageTools.decodeSmall(resolver, p.uri, 256)
            val hv = DupHash.perceptualHash(bmp)
            bmp.recycle()
            hv
        } catch (e: Exception) {
            Log.w("DupHash", "phash failed", e); continue
        }
        val idx = leaders.indexOfFirst { DupHash.hamming(it.first, h) <= DupHash.NEAR_HAMMING }
        if (idx < 0) {
            leaders.add(h to near.size)
            near.add(mutableListOf(p))
        } else {
            near[idx].add(p)
        }
        if (i % 25 == 0) progress(i, candidates.size, "comparing")
    }
    progress(candidates.size, candidates.size, "comparing")

    val nearClusters = near
        .mapNotNull { members ->
            if (members.size >= 2) DupCluster(false, members.toSortedDescendingByDate()) else null
        }

    (exactClusters + nearClusters)
        .sortedByDescending { it.reclaimableBytes }
    }

private fun List<Photo>.toSortedDescendingByDate(): List<Photo> =
    sortedByDescending { it.dateTakenMs }

/**
 * Deletes the given photos via MediaStore. On Android 14+ the system shows its own
 * per-photo delete confirmation popup, so this is user-confirmed by the OS as well.
 * Returns (deletedCount, freedBytes).
 */
suspend fun deletePhotos(resolver: ContentResolver, photos: List<Photo>): Pair<Int, Long> = withContext(Dispatchers.IO) {
    var ok = 0
    var freed = 0L
    for (p in photos.sortedByDescending { it.sizeBytes }) {
        val n = runCatching { resolver.delete(p.uri, null, null) }.getOrDefault(-1)
        if (n > 0) {
            ok++
            freed += p.sizeBytes
        }
    }
    ok to freed
}
