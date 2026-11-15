package com.kowsiq.creativescan

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kowsiq.creativescan.TestSupport.log
import com.kowsiq.creativescan.core.IdCardLayout
import com.kowsiq.creativescan.data.DocumentRepository
import com.kowsiq.creativescan.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlin.math.abs

/**
 * Drives the real ViewModel exactly like the camera screen does (it hands over a captured
 * JPEG file), then exports and re-opens the results with Android's own PDF renderer.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private lateinit var vm: AppViewModel

    // Android 8/9 need the storage permission to write into Documents/Creative Scan (the app asks for it
    // before exporting; on Android 10+ MediaStore needs none).
    private val storagePermissions: Array<String> =
        if (Build.VERSION.SDK_INT <= 28) {
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            emptyArray()
        }

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*storagePermissions)

    @Before
    fun setUp() {
        assertTrue(OpenCVLoader.initLocal())
        instr.runOnMainSync { vm = AppViewModel(TestSupport.ctx.applicationContext as Application) }
        instr.runOnMainSync { vm.clearSession() }
    }

    @After
    fun cleanUp() {
        TestSupport.ctx.let { c ->
            DocumentRepository.list(c).filter { it.name.startsWith("CSTEST") }.forEach { DocumentRepository.delete(c, it) }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 90_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what (message='${vm.message}', busy='${vm.busy}')")
    }

    private fun capture(scene: TestSupport.Scene, name: String): File {
        val f = File(TestSupport.ctx.cacheDir, "$name.jpg")
        TestSupport.saveJpegFile(scene.bitmap, f, 95)
        instr.runOnMainSync { vm.onPhotoCaptured(f) }
        return f
    }

    @Test
    fun idCardTwoSidesLandInTheMiddleOfA4_thenPdfAndJpegExportWork() {
        // ---------- ID card: front, then back ----------
        instr.runOnMainSync { vm.startIdCardScan() }
        capture(TestSupport.cardScene(0), "front")
        waitFor("front side processed") { vm.idCardAwaitingBack && vm.busy == null }
        assertNotNull("front preview missing", vm.idCardFrontPreview)
        instr.runOnMainSync { vm.beginBackCapture() }
        capture(TestSupport.cardScene(1), "back")
        waitFor("A4 page created") { vm.pages.size == 1 && vm.busy == null }

        val idPage = vm.pages[0]
        assertTrue("page must be flagged as a complete A4 sheet", idPage.isIdCardCopy)
        val sheet = BitmapFactory.decodeFile(idPage.file.path)
        assertNotNull(sheet)
        TestSupport.save("04_id_a4_page", sheet, 1100)
        assertEquals(IdCardLayout.PAGE_WIDTH.toInt(), sheet.width)
        assertEquals(IdCardLayout.PAGE_HEIGHT.toInt(), sheet.height)
        val b = TestSupport.contentBounds(sheet)
        val leftMargin = b[0]; val rightMargin = sheet.width - b[2]
        val topMargin = b[1]; val bottomMargin = sheet.height - b[3]
        val expectedW = IdCardLayout.CARD_WIDTH
        val expectedH = IdCardLayout.cardHeight * 2 + IdCardLayout.GAP
        log("ID A4 content bounds=${b.toList()} margins L=$leftMargin R=$rightMargin T=$topMargin B=$bottomMargin size=${b[2] - b[0]}x${b[3] - b[1]} expected=${expectedW}x$expectedH")
        assertTrue("not centred horizontally ($leftMargin vs $rightMargin)", abs(leftMargin - rightMargin) <= 6)
        assertTrue("not centred vertically ($topMargin vs $bottomMargin)", abs(topMargin - bottomMargin) <= 6)
        assertEquals(expectedW.toDouble(), (b[2] - b[0]).toDouble(), 8.0)
        assertEquals(expectedH.toDouble(), (b[3] - b[1]).toDouble(), 10.0)
        // the red stamp of the front card must survive the whole pipeline
        val layout = IdCardLayout.compute()
        val sx = (layout.front.left + layout.front.width * TestSupport.STAMP_U).toInt()
        val sy = (layout.front.top + layout.front.height * TestSupport.STAMP_V).toInt()
        val stamp = TestSupport.avgColor(sheet, sx, sy, 8)
        log("front stamp colour on A4 = ${stamp.toList()}")
        assertTrue("stamp colour lost: ${stamp.toList()}", stamp[0] > 150 && stamp[1] < 110)
        sheet.recycle()

        // ---------- add an ordinary page after the ID sheet ----------
        capture(TestSupport.paperScene(), "paper")
        waitFor("regular page added") { vm.pages.size == 2 && vm.busy == null }
        val corners = vm.pages[1].corners
        val expected = TestSupport.paperScene().corners
        val err = (0 until 4).maxOf { maxOf(abs(corners[it].x - expected[it].x), abs(corners[it].y - expected[it].y)) }
        log("regular page auto-corners maxErr=$err")
        assertTrue("auto-detected corners off by $err", err < 0.03f)

        // ---------- PDF ----------
        instr.runOnMainSync { vm.docName = "CSTEST_doc" }
        instr.runOnMainSync { vm.exportPdf() }
        waitFor("PDF export") { vm.exportResult != null || (vm.message?.contains("failed") == true) }
        val pdfResult = vm.exportResult
        assertNotNull("PDF export failed: ${vm.message}", pdfResult)
        val pdfUri = pdfResult!!.uris.first()
        val pfd = TestSupport.ctx.contentResolver.openFileDescriptor(pdfUri, "r")!!
        PdfRenderer(pfd).use { r ->
            assertEquals("page count", 2, r.pageCount)
            r.openPage(0).use { p ->
                log("PDF page 1 = ${p.width}x${p.height} pt")
                assertTrue("ID page must be exactly A4 portrait", abs(p.width - 595) <= 1 && abs(p.height - 842) <= 1)
                val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                TestSupport.save("05_pdf_page1", bmp, 1000)
                val pb = TestSupport.contentBounds(bmp)
                val dx = abs(pb[0] - (bmp.width - pb[2])); val dy = abs(pb[1] - (bmp.height - pb[3]))
                log("PDF page 1 content bounds=${pb.toList()} centring offsets dx=$dx dy=$dy (px at 2x)")
                assertTrue("PDF card pair not centred (dx=$dx dy=$dy)", dx <= 8 && dy <= 8)
                bmp.recycle()
            }
            r.openPage(1).use { p ->
                log("PDF page 2 = ${p.width}x${p.height} pt")
                val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                TestSupport.save("05_pdf_page2", bmp, 1000)
                val ink = TestSupport.inkFraction(bmp, 120)
                log("PDF page 2 ink fraction=$ink")
                assertTrue("second page looks blank", ink > 0.003)
                bmp.recycle()
            }
        }
        pfd.close()

        // ---------- JPEG ----------
        instr.runOnMainSync { vm.exportResult = null }
        instr.runOnMainSync { vm.exportJpegs() }
        waitFor("JPEG export") { vm.exportResult != null || (vm.message?.contains("failed") == true) }
        val jpgResult = vm.exportResult
        assertNotNull("JPEG export failed: ${vm.message}", jpgResult)
        assertEquals(2, jpgResult!!.uris.size)
        jpgResult.uris.forEachIndexed { i, uri ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            TestSupport.ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
            log("JPEG ${i + 1}: ${o.outWidth}x${o.outHeight}")
            assertTrue("JPEG ${i + 1} unreadable", o.outWidth > 100 && o.outHeight > 100)
            if (i == 0) {
                assertEquals(IdCardLayout.PAGE_WIDTH.toInt(), o.outWidth)
                assertEquals(IdCardLayout.PAGE_HEIGHT.toInt(), o.outHeight)
            }
        }

        // the files are listed in the document manager
        val listed = DocumentRepository.list(TestSupport.ctx).filter { it.name.startsWith("CSTEST_doc") }.map { it.name }
        log("listed documents: $listed")
        assertTrue("PDF not listed: $listed", listed.contains("CSTEST_doc.pdf"))
        assertTrue("JPEG not listed: $listed", listed.any { it.endsWith(".jpg") })
    }

    @Test
    fun cardWithoutVisibleEdgesIsRejectedWithAMessage() {
        instr.runOnMainSync { vm.startIdCardScan() }
        val blank = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        capture(TestSupport.Scene(blank, emptyList()), "blank")
        waitFor("rejection message") { vm.message != null && vm.busy == null }
        log("blank card rejection message: ${vm.message}")
        assertTrue(vm.pages.isEmpty())
        assertTrue("should still be waiting for the front side", !vm.idCardAwaitingBack)
    }
}
