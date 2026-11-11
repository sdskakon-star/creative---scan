package com.kowsiq.creativescan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfWriterTest {

    /** Stand-in JPEG payload (SOI ... EOI). The writer only embeds the bytes, it never decodes them. */
    private fun payload(tag: Int, size: Int = 64): ByteArray {
        val b = ByteArray(size) { ((tag * 31 + it) and 0x7F).toByte() }
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte()
        b[size - 2] = 0xFF.toByte(); b[size - 1] = 0xD9.toByte()
        return b
    }

    private fun jpeg(w: Int, h: Int, tag: Int, fullA4: Boolean = false) = PdfImage(payload(tag), w, h, fullA4)

    private fun write(pages: List<PdfImage>, fit: Boolean, dpi: Int = 300): ByteArray {
        val out = ByteArrayOutputStream()
        PdfWriter(out).write(pages.map { p -> { p } }, fit, dpi)
        return out.toByteArray()
    }

    private fun text(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    @Test
    fun structureAndXrefAreValid() {
        val pdf = write(listOf(jpeg(100, 140, 1), jpeg(140, 100, 2)), fit = true)
        val t = text(pdf)
        assertTrue(t.startsWith("%PDF-1.4"))
        assertTrue(t.trimEnd().endsWith("%%EOF"))
        val xrefPos = Regex("startxref\\n(\\d+)\\n").find(t)!!.groupValues[1].toInt()
        assertTrue(t.substring(xrefPos).startsWith("xref"))
        val entries = Regex("(\\d{10}) 00000 n ").findAll(t.substring(xrefPos)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(8, entries.size) // catalog, page tree and 3 objects per page
        entries.forEachIndexed { i, off ->
            assertTrue("object ${i + 1} offset", t.startsWith("${i + 1} 0 obj", off))
        }
        assertTrue(t.contains("/Count 2"))
    }

    @Test
    fun embeddedJpegStreamsAreIntactAndInPageOrder() {
        val p1 = payload(1, 80); val p2 = payload(2, 96); val p3 = payload(3, 72)
        val pdf = write(
            listOf(PdfImage(p1, 10, 10), PdfImage(p2, 10, 10), PdfImage(p3, 10, 10)), fit = true
        )
        val t = text(pdf)
        val streams = Regex("/Filter /DCTDecode /Length (\\d+) >>\nstream\n").findAll(t).toList()
        assertEquals(3, streams.size)
        val expected = listOf(p1, p2, p3)
        streams.forEachIndexed { i, m ->
            val len = m.groupValues[1].toInt()
            assertEquals(expected[i].size, len)
            val start = m.range.last + 1
            assertArrayEquals("page ${i + 1} bytes", expected[i], pdf.copyOfRange(start, start + len))
            assertTrue(t.startsWith("\nendstream", start + len))
        }
        // Page tree lists the pages in the same order as the image objects
        assertTrue(t.contains("/Kids [3 0 R 6 0 R 9 0 R] /Count 3"))
    }

    @Test
    fun fullA4IdCardPageIsEdgeToEdgeOnA4Portrait() {
        val pdf = text(write(listOf(jpeg(165, 234, 4, true)), fit = true))
        assertTrue(pdf.contains("/MediaBox [0 0 595.28 841.89]"))
        assertTrue(pdf.contains("q 595.28 0 0 841.89 0.00 0.00 cm /Im0 Do Q"))
    }

    @Test
    fun fullA4IdCardPageKeepsLandscapeWhenRotated() {
        val pdf = text(write(listOf(jpeg(234, 165, 4, true)), fit = true))
        assertTrue(pdf.contains("/MediaBox [0 0 841.89 595.28]"))
        assertTrue(pdf.contains("q 841.89 0 0 595.28 0.00 0.00 cm /Im0 Do Q"))
    }

    @Test
    fun regularFitToPageIsCentredInsideMargins() {
        // square image on A4 portrait: limited by the width, centred vertically
        val pdf = text(write(listOf(jpeg(100, 100, 5)), fit = true))
        assertTrue(pdf.contains("/MediaBox [0 0 595.28 841.89]"))
        val m = Regex("q ([\\d.]+) 0 0 ([\\d.]+) ([\\d.]+) ([\\d.]+) cm").find(pdf)!!
        val (dw, dh, x, y) = m.groupValues.drop(1).map { it.toDouble() }
        assertEquals(595.28 - 48.0, dw, 0.01)
        assertEquals(dw, dh, 0.01)
        assertEquals((595.28 - dw) / 2, x, 0.01)
        assertEquals((841.89 - dh) / 2, y, 0.01)
    }

    @Test
    fun nativeSizeModeUsesImageDpi() {
        val pdf = text(write(listOf(jpeg(300, 600, 6)), fit = false, dpi = 300))
        assertTrue(pdf.contains("/MediaBox [0 0 72.00 144.00]"))
    }
}
