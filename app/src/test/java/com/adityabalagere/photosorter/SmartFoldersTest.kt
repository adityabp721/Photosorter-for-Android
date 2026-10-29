package com.adityabalagere.photosorter

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.util.TimeZone

class SmartFoldersTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val la = TimeZone.getTimeZone("America/Los_Angeles")

    private fun photo(id: Long, iso: String, off: Int?, path: String = "DCIM/Camera/", title: String = "p$id.jpg") = Photo(
        id = id, uri = uri, title = title, mime = "image/jpeg",
        dateTakenMs = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli(), dateLabel = "",
        width = 1, height = 1, latitude = null, longitude = null, sizeBytes = 100,
        relPath = path, addedMs = id, utcOffsetMin = off,
    )

    private fun ai(type: String, place: String = "") = AiResult(type, "d", type, place)

    @Test fun japanTripCollectsAllPhotosFromThoseDays() {
        // the real test set: Japan photos at +09:00, only the temple names a place; home photos at -07:00
        val ps = listOf(
            photo(1, "2026-09-14T14:34:51+09:00", 540), photo(2, "2026-09-16T13:42:43+09:00", 540),
            photo(3, "2026-09-18T11:24:53+09:00", 540), photo(4, "2026-09-19T10:29:56+09:00", 540),
            photo(5, "2026-09-20T18:07:10+09:00", 540),
            photo(6, "2026-10-19T14:12:47-07:00", -420), photo(7, "2026-10-19T14:18:11-07:00", -420),
            photo(8, "2026-10-19T17:32:00-07:00", -420, "DCIM/Screenshots/", "Screenshot_20261019_173200_Amazon Shopping.jpg"),
            photo(9, "2026-10-18T10:00:00-07:00", null, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
        )
        val res = mapOf(
            1L to ai("events"), 2L to ai("food"), 3L to ai("events", "Japan"), 4L to ai("art"), 5L to ai("events"),
            6L to ai("food"), 7L to ai("food"), 8L to ai("shopping"), 9L to ai("people"),
        )
        val f = SmartFolders.assign(ps, res, zone = la)
        assertEquals(listOf("japan-trip"), (1L..5L).map { f.getValue(it) }.distinct())
        assertEquals("food", f[6L]); assertEquals("food", f[7L])
        assertEquals("screenshots", f[8L])
        assertEquals("whatsapp", f[9L])
    }

    @Test fun unnamedTripGetsMonthName() {
        val ps = listOf(photo(1, "2026-09-14T10:00:00+09:00", 540), photo(2, "2026-09-15T10:00:00+09:00", 540))
        val f = SmartFolders.assign(ps, mapOf(1L to ai("food"), 2L to ai("nature")), zone = la)
        assertEquals("trip-sep-2026", f[1L]); assertEquals("trip-sep-2026", f[2L])
    }

    @Test fun longGapSplitsTrips() {
        val ps = listOf(photo(1, "2026-03-01T10:00:00+01:00", 60), photo(2, "2026-06-01T10:00:00+01:00", 60))
        val res = mapOf(1L to ai("city", "France"), 2L to ai("city", "Italy"))
        val f = SmartFolders.assign(ps, res, zone = la)
        assertEquals("france-trip", f[1L]); assertEquals("italy-trip", f[2L])
    }

    @Test fun gpsCountryMakesATripWithoutTimeZone() {
        val ps = listOf(photo(1, "2026-07-01T10:00:00Z", null))
        val f = SmartFolders.assign(ps, mapOf(1L to ai("nature")), mapOf(1L to "United Kingdom"), "United States", la)
        assertEquals("uk-trip", f[1L])
    }

    @Test fun homePhotosAreNotTrips() {
        val ps = listOf(photo(1, "2026-07-01T10:00:00-07:00", -420), photo(2, "2026-01-10T10:00:00-08:00", -480))
        val f = SmartFolders.assign(ps, mapOf(1L to ai("nature"), 2L to ai("pets")), zone = la)
        assertEquals("nature", f[1L]); assertEquals("pets", f[2L])
    }

    @Test fun normalizesTypesAndPlaces() {
        assertEquals("screenshots", SmartFolders.normalizeType("Screenshot."))
        assertEquals("pets", SmartFolders.normalizeType("animals"))
        assertEquals("other", SmartFolders.normalizeType("spaceship"))
        assertEquals("", SmartFolders.normalizePlace("none"))
        assertEquals("usa", SmartFolders.placeSlug("United States"))
        assertEquals(540, ImageTools.parseOffset("+09:00")); assertEquals(-420, ImageTools.parseOffset("-07:00"))
    }

    @Test fun parsesStructuredAnswers() {
        val a = VlmAi.parseOutput("type: food\nplace: none\ndescription: colorful ramen bowl")!!
        assertEquals("food", a.type); assertEquals("", a.place); assertEquals("colorful ramen bowl", a.description)
        val b = VlmAi.parseOutput("type: events  place: Japan  description: crowds at a temple")!!
        assertEquals("events", b.type); assertEquals("Japan", b.place); assertEquals("crowds at a temple", b.description)
    }

    @Test fun liteCaptionsMapToTypes() {
        // real SmolVLM2-500M captions of the test photos
        val cases = mapOf(
            "A plate of food with a fork and spoon on it." to "food",
            "A close-up of a pan filled with a creamy orange curry, garnished with dill and herbs." to "food",
            "A pot of orange batter and a baking tray with round balls of batter are on a white stove." to "food",
            "Three people are playing traditional Chinese instruments in a room." to "events",
            "Three people are on stage in a room with a fire mural on the wall." to "events",
            "The photo captures the grandeur of a traditional Chinese pavilion surrounded by lush greenery." to "travel",
            "A large circular emblem with a bird and a circle in the center, surrounded by intricate patterns." to "art",
            "A woman in a brown sweater stands on a rocky shore at sunset." to "people",
            "A snowy mountain behind a lake." to "nature",
            "Nothing recognizable here." to "other",
        )
        for ((caption, type) in cases) {
            val r = VlmAi.liteResult(caption)!!
            assertEquals(caption, type, r.type)
            assertEquals("", r.place)
            assertEquals(caption.trimEnd('.'), r.description)
        }
        assertEquals(null, VlmAi.liteResult("  \n "))
    }

    @Test fun exactCopiesKeepTheOriginalNotTheAppCopy() {
        // the app's own copy was added later and lives in Pictures/PhotoSorter/ -> the camera original is kept
        val original = photo(10, "2026-10-04T20:00:59-07:00", -420, "DCIM/Camera/").copy(addedMs = 1000)
        val copy = photo(11, "2026-10-04T20:00:59-07:00", -420, "Pictures/PhotoSorter/other/").copy(addedMs = 2000)
        assertEquals(listOf(10L, 11L), listOf(copy, original).originalFirst().map { it.id })
        assertEquals(listOf(11L), DupCluster(true, listOf(copy, original).originalFirst()).defaultDelete.map { it.id })
    }

    @Test fun tripDatesUseThePhotosOwnTimeZone() {
        // 10:45 in Kyoto is still the previous evening in Los Angeles
        val t = TripCluster(1).apply {
            add(photo(1, "2026-09-14T10:45:00+09:00", 540))
            add(photo(2, "2026-09-16T09:10:00+09:00", 540))
        }
        assertEquals("2026-09-14 → 2026-09-16", t.dateRangeLabel(la))
        val home = TripCluster(2).apply { add(photo(3, "2026-10-19T23:30:00-07:00", null)) }
        assertEquals("2026-10-19", home.dateRangeLabel(la))
    }
}
