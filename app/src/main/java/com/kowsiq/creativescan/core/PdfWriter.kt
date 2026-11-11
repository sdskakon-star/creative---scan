package com.kowsiq.creativescan.core

import java.io.OutputStream
import java.util.Locale

class PdfImage(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Minimal, dependency-free PDF writer. Each page embeds the scan as a JPEG (DCTDecode)
 * stream, so the quality and file size are fully under our control and nothing is
 * re-compressed. Pages are written one at a time to keep memory use low.
 */
class PdfWriter(private val out: OutputStream) {

    private var position = 0L
    private lateinit var offsets: LongArray

    /**
     * @param providers one lambda per page, called lazily so only one page is in memory
     * @param fitToPage true: A4 page (auto portrait/landscape), image scaled to fit inside
     *                  the margins keeping its aspect ratio. false: the page has the image's
     *                  own size at [dpi] with no margins.
     */
    fun write(providers: List<() -> PdfImage>, fitToPage: Boolean, dpi: Int) {
        val n = providers.size
        require(n > 0) { "No pages" }
        offsets = LongArray(3 + 3 * n)

        text("%PDF-1.4\n")
        bytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        object1(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val kids = (0 until n).joinToString(" ") { "${3 + 3 * it} 0 R" }
        object1(2, "<< /Type /Pages /Kids [$kids] /Count $n >>")

        for (i in 0 until n) {
            val pageId = 3 + 3 * i
            val contentId = 4 + 3 * i
            val imageId = 5 + 3 * i
            val img = providers[i]()

            val pw: Double; val ph: Double
            val dw: Double; val dh: Double
            val x: Double; val y: Double
            if (fitToPage) {
                val landscape = img.width > img.height
                pw = if (landscape) A4_H else A4_W
                ph = if (landscape) A4_W else A4_H
                val aw = pw - 2 * MARGIN
                val ah = ph - 2 * MARGIN
                val k = minOf(aw / img.width, ah / img.height)
                dw = img.width * k; dh = img.height * k
                x = (pw - dw) / 2; y = (ph - dh) / 2
            } else {
                pw = img.width * 72.0 / dpi
                ph = img.height * 72.0 / dpi
                dw = pw; dh = ph; x = 0.0; y = 0.0
            }

            object1(
                pageId,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                    "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>"
            )
            val content = "q ${f(dw)} 0 0 ${f(dh)} ${f(x)} ${f(y)} cm /Im0 Do Q\n"
            object1(contentId, "<< /Length ${content.length} >>\nstream\n${content}endstream")

            offsets[imageId] = position
            text("$imageId 0 obj\n")
            text(
                "<< /Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${img.jpeg.size} >>\nstream\n"
            )
            bytes(img.jpeg)
            text("\nendstream\nendobj\n")
        }

        val xrefPos = position
        text("xref\n0 ${offsets.size}\n")
        text("0000000000 65535 f \n")
        for (id in 1 until offsets.size) {
            text(String.format(Locale.US, "%010d 00000 n \n", offsets[id]))
        }
        text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        out.flush()
    }

    private fun object1(id: Int, body: String) {
        offsets[id] = position
        text("$id 0 obj\n$body\nendobj\n")
    }

    private fun f(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun text(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))

    private fun bytes(b: ByteArray) {
        out.write(b)
        position += b.size
    }

    private companion object {
        const val A4_W = 595.28
        const val A4_H = 841.89
        const val MARGIN = 24.0
    }
}
