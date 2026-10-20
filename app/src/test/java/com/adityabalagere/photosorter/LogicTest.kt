package com.adityabalagere.photosorter

import android.net.Uri
import org.mockito.Mockito
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {

    // android.net.Uri is stubbed on the JVM; grouping never touches it.
    private val uri: Uri = Mockito.mock(Uri::class.java)

    private fun photo(id: Long, ms: Long) = Photo(
        id = id, uri = uri, title = "p$id.jpg", mime = "image/jpeg",
        dateTakenMs = ms, dateLabel = "", width = 1, height = 1,
        latitude = null, longitude = null, sizeBytes = 100,
    )

    @Test fun canonicalKeyMergesTripVariants() {
        assertEquals("japan", Trips.canonicalKey("japan-trip"))
        assertEquals("japan", Trips.canonicalKey("Japan"))
        assertEquals("japan", Trips.canonicalKey("trip-japan"))
        assertEquals("swiss", Trips.canonicalKey("swiss-travel"))
        assertEquals("food", Trips.canonicalKey("food"))
    }

    @Test fun groupUsesMostCommonNameAndSortsNewestFirst() {
        val ps = listOf(photo(1, 1000), photo(2, 2000), photo(3, 3000), photo(4, 9000))
        val res = mapOf(
            1L to AiResult("japan-trip", "a"),
            2L to AiResult("japan-trip", "b"),
            3L to AiResult("japan", "c"),
            4L to AiResult("food", "d"),
        )
        val g = Trips.group(ps, res)
        assertEquals(listOf("food", "japan-trip"), g.map { it.folder })
        assertEquals(3, g[1].photos.size)
    }

    @Test fun groupSkipsPhotosWithoutResults() {
        val g = Trips.group(listOf(photo(1, 1), photo(2, 2)), mapOf(1L to AiResult("x", "y")))
        assertEquals(1, g.single().photos.size)
    }

    @Test fun vlmParsesPlainAnswer() {
        val r = VlmAi.parseOutput("folder: japan-trip\ndescription: Shrine gate in Kyoto.")!!
        assertEquals("japan-trip", r.folder)
        assertEquals("Shrine gate in Kyoto.", r.description)
    }

    @Test fun vlmParsesMarkdownAndSanitizes() {
        val r = VlmAi.parseOutput("**folder:** Swiss Trip!\n- **description:** Matterhorn at dawn")!!
        assertEquals("swiss-trip", r.folder)
        assertEquals("Matterhorn at dawn", r.description)
    }

    @Test fun vlmFallsBackToFirstLines() {
        val r = VlmAi.parseOutput("Food\nA bowl of ramen")!!
        assertEquals("food", r.folder)
        assertEquals("A bowl of ramen", r.description)
    }

    @Test fun vlmRejectsEmpty() {
        assertNull(VlmAi.parseOutput("   \n "))
        assertNull(VlmAi.parseOutput("folder: !!!"))
    }

    @Test fun jobProgressFraction() {
        assertNull(JobProgress("x").fraction)
        assertEquals(0.5f, JobProgress("x", 5, 10).fraction!!, 0.001f)
        assertEquals(1f, JobProgress("x", 50, 10).fraction!!, 0.001f)
    }

    @Test fun humanBytesFormats() {
        assertEquals("size unknown", humanBytes(0))
        assertEquals("2 KB", humanBytes(2048))
        assertEquals("1.5 MB", humanBytes(1572864))
    }

    private fun named(id: Long, name: String, mime: String = "image/jpeg") = photo(id, id).copy(title = name, mime = mime)

    @Test fun rawDetection() {
        assertEquals(true, named(1, "a.DNG").isRaw)
        assertEquals(true, named(2, "a.bin", "image/x-adobe-dng").isRaw)
        assertEquals(false, named(3, "a.jpg").isRaw)
    }

    @Test fun onlyExactNonRawCopiesArePreselected() {
        val exact = DupCluster(true, listOf(named(1, "a.jpg"), named(2, "b.jpg"), named(3, "c.dng")))
        assertEquals(listOf(2L), exact.defaultDelete.map { it.id })
        val alike = DupCluster(false, listOf(named(4, "x.jpg"), named(5, "x.dng")))
        assertEquals(emptyList<Photo>(), alike.defaultDelete)
        assertEquals(true, alike.hasRaw)
    }
}
