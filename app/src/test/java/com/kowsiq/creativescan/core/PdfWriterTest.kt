package com.kowsiq.creativescan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Test

class PdfWriterTest {

    private fun jpeg(w: Int, h: Int, rgb: Int): PdfImage = jpeg(w, h, rgb, false)

    private fun jpeg(w: Int, h: Int, rgb: Int, fullA4: Boolean): PdfImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, rgb)
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, "jpg", out))
        return PdfImage(out.toByteArray(), w, h, fullA4)
    }

    private fun write(pages: List<PdfImage>, fit: Boolean, dpi: Int = 300): ByteArray {
        val out = ByteArrayOutputStream()
        PdfWriter(out).write(pages.map { p -> { p } }, fit, dpi)
        return out.toByteArray()
    }

    private fun text(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    @Test
    fun structureAndXrefAreValid() {
        val pdf = write(listOf(jpeg(100, 140, 0xFF0000), jpeg(140, 100, 0x00FF00)), fit = true)
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
    fun embeddedJpegStreamsDecodeAndKeepOrder() {
        val red = 0xFF0000; val green = 0x00FF00
        val pdf = write(listOf(jpeg(64, 64, red), jpeg(64, 64, green)), fit = true)
        val t = text(pdf)
        val streams = Regex("/Filter /DCTDecode /Length (\\d+) >>\\nstream\\n").findAll(t).toList()
        assertEquals(2, streams.size)
        val colours = streams.map { m ->
            val len = m.groupValues[1].toInt()
            val start = m.range.last + 1
            val decoded = ImageIO.read(ByteArrayInputStream(pdf, start, len))
            assertNotNull(decoded)
            decoded.getRGB(32, 32) and 0xFFFFFF
        }
        // page 1 is predominantly red, page 2 predominantly green (JPEG is lossy)
        assertTrue((colours[0] shr 16) > 200 && (colours[0] and 0xFF) < 60)
        assertTrue(((colours[1] shr 8) and 0xFF) > 200 && (colours[1] shr 16) < 60)
    }

    @Test
    fun fullA4IdCardPageIsEdgeToEdgeOnA4Portrait() {
        val pdf = text(write(listOf(jpeg(165, 234, 0xFFFFFF, true)), fit = true))
        assertTrue(pdf.contains("/MediaBox [0 0 595.28 841.89]"))
        assertTrue(pdf.contains("q 595.28 0 0 841.89 0.00 0.00 cm /Im0 Do Q"))
    }

    @Test
    fun fullA4IdCardPageKeepsLandscapeWhenRotated() {
        val pdf = text(write(listOf(jpeg(234, 165, 0xFFFFFF, true)), fit = true))
        assertTrue(pdf.contains("/MediaBox [0 0 841.89 595.28]"))
        assertTrue(pdf.contains("q 841.89 0 0 595.28 0.00 0.00 cm /Im0 Do Q"))
    }

    @Test
    fun regularFitToPageIsCentredInsideMargins() {
        // square image on A4 portrait: limited by the width, centred vertically
        val pdf = text(write(listOf(jpeg(100, 100, 0x808080)), fit = true))
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
        val pdf = text(write(listOf(jpeg(300, 600, 0xFFFFFF)), fit = false, dpi = 300))
        assertTrue(pdf.contains("/MediaBox [0 0 72.00 144.00]"))
    }
}
