package com.example.photosorter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * A group of photos the AI filed under the same folder (e.g. "japan-trip").
 */
class TripCluster(
    val id: Int,
    val photos: MutableList<Photo> = mutableListOf(),
) {
    var folder: String = ""
    var summary: String = ""

    /** Per-photo filing progress, UI-visible. */
    var filed: Int by mutableIntStateOf(0)

    var firstMs: Long = Long.MAX_VALUE
        private set
    var lastMs: Long = Long.MIN_VALUE
        private set

    fun add(p: Photo) {
        photos.add(p)
        firstMs = min(firstMs, p.dateTakenMs)
        lastMs = maxOf(lastMs, p.dateTakenMs)
    }

    val dateRangeLabel: String
        get() {
            if (photos.isEmpty()) return "unknown dates"
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val a = fmt.format(Date(firstMs))
            val b = fmt.format(Date(lastMs))
            return if (a == b) a else "$a → $b"
        }
}

object Trips {

    /**
     * Grouping key so slightly different AI answers land in one folder:
     * "japan-trip", "japan", "Trip Japan" all normalize to "japan".
     */
    fun canonicalKey(folder: String): String {
        var k = folder.lowercase().trim()
        while (true) {
            val n = when {
                k.endsWith("-trip") -> k.dropLast(5)
                k.endsWith("-travel") -> k.dropLast(7)
                k.startsWith("trip-") -> k.removePrefix("trip-")
                else -> null
            } ?: break
            k = n.trim('-')
        }
        return k.ifEmpty { folder.lowercase().trim() }
    }

    /**
     * Groups successfully-scanned photos by canonical folder. The group's display name is the
     * most common exact folder string the AI returned for it. Newest groups first.
     */
    fun group(photos: List<Photo>, results: Map<Long, AiResult>): List<TripCluster> {
        val buckets = LinkedHashMap<String, MutableList<Pair<Photo, AiResult>>>()
        for (p in photos) {
            val r = results[p.id] ?: continue
            buckets.getOrPut(canonicalKey(r.folder)) { mutableListOf() }.add(p to r)
        }
        return buckets.entries.mapIndexed { idx, e ->
            val counts = e.value.groupingBy { it.second.folder }.eachCount()
            val name = counts.maxByOrNull { it.value }?.key ?: canonicalKey(e.key)
            val mid = e.value.sortedBy { it.first.dateTakenMs }.get(e.value.size / 2)
            TripCluster(id = idx).apply {
                folder = name
                summary = mid.second.description
                e.value.forEach { (p, _) -> add(p) }
            }
        }.sortedByDescending { it.lastMs }
    }
}
