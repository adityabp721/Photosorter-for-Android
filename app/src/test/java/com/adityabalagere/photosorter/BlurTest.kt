package com.adityabalagere.photosorter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class BlurTest {
    private val w = 512
    private val h = 384

    /** Fine detail everywhere: random texture. */
    private fun texture(seed: Int = 1): FloatArray {
        val r = Random(seed)
        return FloatArray(w * h) { 40f + r.nextFloat() * 180f }
    }

    /** Repeated 5x5 box blur, roughly what an out-of-focus lens does. */
    private fun blur(src: FloatArray, passes: Int): FloatArray {
        var a = src
        repeat(passes) {
            val b = FloatArray(a.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val yy = y + dy; val xx = x + dx
                    if (yy in 0 until h && xx in 0 until w) { s += a[yy * w + xx]; n++ }
                }
                b[y * w + x] = s / n
            }
            a = b
        }
        return a
    }

    private fun kindOf(g: FloatArray) = BlurScore.classify(BlurScore.measure(g, w, h))

    @Test fun sharpPhotoIsKept() = assertNull(kindOf(texture()))

    @Test fun blurredPhotoIsFlagged() = assertEquals(BlurItem.Kind.BLURRY, kindOf(blur(texture(), 3)))

    @Test fun blankAndBlackFramesAreDark() {
        assertEquals(BlurItem.Kind.DARK, kindOf(FloatArray(w * h) { 128f }))
        assertEquals(BlurItem.Kind.DARK, kindOf(FloatArray(w * h) { 4f }))
    }

    @Test fun sharpSubjectOnPlainBackgroundIsKept() {
        // a sharp object in one corner of an otherwise smooth gradient "sky"
        val t = texture(7)
        val g = FloatArray(w * h) { i -> 150f + 40f * (i / w) / h }
        for (y in 0 until h / 4) for (x in 0 until w / 4) g[y * w + x] = t[y * w + x]
        assertNull(kindOf(g))
    }

    @Test fun skipsScreenshotsRawAndOwnCopies() {
        fun photo(title: String, mime: String = "image/jpeg", rel: String = "DCIM/Camera/") =
            Photo(
                id = 1, uri = org.mockito.Mockito.mock(android.net.Uri::class.java), title = title, mime = mime,
                dateTakenMs = 0, dateLabel = "", width = 4000, height = 3000, latitude = null, longitude = null,
                sizeBytes = 1, relPath = rel,
            )
        assertEquals(true, photo("a.jpg").blurCandidate())
        assertEquals(false, photo("a.dng", "image/x-adobe-dng").blurCandidate())
        assertEquals(false, photo("a.gif", "image/gif").blurCandidate())
        assertEquals(false, photo("s.jpg", rel = "Pictures/Screenshots/").blurCandidate())
        assertEquals(false, photo("c.jpg", rel = "Pictures/PhotoSorter/food/").blurCandidate())
    }
}
