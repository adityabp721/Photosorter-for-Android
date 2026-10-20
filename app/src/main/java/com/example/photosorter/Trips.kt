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
    fun group(photos: List<Photo>, results: Map<Long, AiResult>, folders: Map<Long, String> = emptyMap()): List<TripCluster> {
        val buckets = LinkedHashMap<String, MutableList<Pair<Photo, AiResult>>>()
        for (p in photos) {
            val r0 = results[p.id] ?: continue
            val r = folders[p.id]?.let { r0.copy(folder = it) } ?: r0
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

/**
 * Turns per-photo AI answers (type + place) into folders:
 * - screenshots, WhatsApp images and documents always go to their own folder;
 * - photos taken AWAY from home (EXIF time-zone offset differs from the phone's home zone at that
 *   date, or GPS country differs from the home country) are split into trips - runs of away photos
 *   with no gap over [TRIP_GAP_MS] - and every photo of a trip (food, people, sights...) goes to
 *   "<country>-trip", the country voted from GPS lookups and the AI's place answers;
 * - everything else goes to its type folder (food, people, pets...).
 */
object SmartFolders {

    val TYPES = listOf(
        "travel", "food", "people", "pets", "nature", "city", "home", "shopping",
        "documents", "screenshots", "vehicles", "events", "art", "other",
    )
    private val SYNONYMS = mapOf(
        "screenshot" to "screenshots", "document" to "documents", "pet" to "pets", "animals" to "pets",
        "animal" to "pets", "person" to "people", "family" to "people", "selfie" to "people",
        "landscape" to "nature", "scenery" to "nature", "car" to "vehicles", "vehicle" to "vehicles",
        "event" to "events", "concert" to "events", "work" to "documents", "receipt" to "documents",
        "drink" to "food", "restaurant" to "food", "product" to "shopping", "products" to "shopping",
        "architecture" to "city", "building" to "city", "fashion" to "people", "sports" to "events",
    )
    private val COUNTRY_SLUGS = mapOf(
        "united states" to "usa", "united states of america" to "usa", "us" to "usa", "u.s." to "usa",
        "united kingdom" to "uk", "great britain" to "uk", "england" to "uk",
        "united arab emirates" to "uae", "south korea" to "korea", "republic of korea" to "korea",
    )

    const val TRIP_GAP_MS = 2L * 24 * 3600 * 1000

    /** Model answer -> one of [TYPES] ("Screenshot", "pets." -> "screenshots", "pets"). */
    fun normalizeType(raw: String): String {
        val t = raw.lowercase().trim().trim('.', '"', '\'', '*').substringBefore(' ').substringBefore(',')
        return when {
            t in TYPES -> t
            SYNONYMS.containsKey(t) -> SYNONYMS.getValue(t)
            else -> "other"
        }
    }

    /** "Japan." -> "Japan"; "none" / "unknown" / "" -> "". */
    fun normalizePlace(raw: String?): String {
        val p = raw?.trim()?.trim('.', '"', '\'', '*')?.trim() ?: return ""
        return if (p.isEmpty() || p.lowercase() in setOf("none", "unknown", "n/a", "na", "null", "home")) "" else p
    }

    /** "Japan" -> "japan", "United States" -> "usa". */
    fun placeSlug(place: String): String {
        val k = place.lowercase().trim()
        return COUNTRY_SLUGS[k] ?: k.replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    /** Home time-zone offset (minutes) at [ms], DST included. */
    fun homeOffsetMin(ms: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int =
        zone.getOffset(ms) / 60000

    /**
     * @param geoCountry country names from the phone's geocoder for photos with GPS (may be empty)
     * @param homeCountry the phone's country name (e.g. "United States"), or null if unknown
     * @return photo id -> folder name, for every photo that has an AI result
     */
    fun assign(
        photos: List<Photo>,
        results: Map<Long, AiResult>,
        geoCountry: Map<Long, String> = emptyMap(),
        homeCountry: String? = null,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Map<Long, String> {
        val scanned = photos.filter { results.containsKey(it.id) }
        fun typeOf(p: Photo) = when {
            p.isScreenshot -> "screenshots"
            p.isWhatsApp -> "whatsapp"
            else -> results.getValue(p.id).type.ifEmpty { normalizeType(results.getValue(p.id).folder) }
        }
        fun fixedFolder(t: String) = t == "screenshots" || t == "documents" || t == "whatsapp"
        fun away(p: Photo): Boolean {
            if (p.dateTakenMs <= 0) return false
            val off = p.utcOffsetMin
            if (off != null && off != homeOffsetMin(p.dateTakenMs, zone)) return true
            val g = geoCountry[p.id]
            return g != null && homeCountry != null && !g.equals(homeCountry, ignoreCase = true)
        }

        // 1. trips = runs of away photos, split on long gaps or a different time zone
        val awayPhotos = scanned.filter { !fixedFolder(typeOf(it)) && away(it) }.sortedBy { it.dateTakenMs }
        val trips = ArrayList<MutableList<Photo>>()
        for (p in awayPhotos) {
            val cur = trips.lastOrNull()
            val prev = cur?.last()
            val sameZone = prev?.utcOffsetMin == null || p.utcOffsetMin == null || prev.utcOffsetMin == p.utcOffsetMin
            if (prev != null && p.dateTakenMs - prev.dateTakenMs <= TRIP_GAP_MS && sameZone) cur.add(p)
            else trips.add(mutableListOf(p))
        }
        val out = HashMap<Long, String>()
        val monthFmt = java.text.SimpleDateFormat("MMM-yyyy", java.util.Locale.US)
        for (trip in trips) {
            // name: GPS countries count double, then the AI's place answers
            val votes = HashMap<String, Int>()
            for (p in trip) {
                geoCountry[p.id]?.let { votes[placeSlug(it)] = (votes[placeSlug(it)] ?: 0) + 2 }
                results.getValue(p.id).place.takeIf { it.isNotEmpty() }?.let { votes[placeSlug(it)] = (votes[placeSlug(it)] ?: 0) + 1 }
            }
            val name = votes.maxByOrNull { it.value }?.key
                ?.let { "$it-trip" }
                ?: "trip-" + monthFmt.format(java.util.Date(trip.first().dateTakenMs)).lowercase()
            trip.forEach { out[it.id] = name }
        }
        // 2. everything else: its type; a clear travel photo with a place but no time info -> that trip
        for (p in scanned) {
            if (out.containsKey(p.id)) continue
            val t = typeOf(p)
            val place = results.getValue(p.id).place
            out[p.id] = when {
                t == "travel" && place.isNotEmpty() && p.utcOffsetMin == null -> "${placeSlug(place)}-trip"
                else -> t
            }
        }
        return out
    }
}
