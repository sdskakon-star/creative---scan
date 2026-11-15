package com.kowsiq.creativescan

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kowsiq.creativescan.TestSupport.log
import com.kowsiq.creativescan.data.DocumentRepository
import com.kowsiq.creativescan.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

/** Stress test: a 30-page document made of 12-megapixel photos must scan, edit-render and export without running out of memory. */
@RunWith(AndroidJUnit4::class)
class ManyPagesTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private lateinit var vm: AppViewModel

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        *(if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE) else emptyArray())
    )

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        assertNotNull(OpenCVLoader.initLocal())
        instr.runOnMainSync { vm = AppViewModel(TestSupport.ctx.applicationContext as Application) }
        instr.runOnMainSync { vm.clearSession() }
    }

    @After
    fun cleanUp() {
        TestSupport.ctx.let { c -> DocumentRepository.list(c).filter { it.name.startsWith("CSTEST") }.forEach { DocumentRepository.delete(c, it) } }
    }

    private fun waitFor(what: String, timeoutMs: Long = 120_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(100) }
        throw AssertionError("Timed out waiting for $what (message='${vm.message}', busy='${vm.busy}')")
    }

    @Test
    fun thirtyTwelveMegapixelPagesExportToPdf() {
        val small = TestSupport.paperScene().bitmap
        val big = Bitmap.createScaledBitmap(small, 4000, 3000, true)
        val template = File(TestSupport.ctx.cacheDir, "big_template.jpg")
        TestSupport.saveJpegFile(big, template, 92)
        big.recycle()
        val rt = Runtime.getRuntime()
        var peak = 0L
        val t0 = System.currentTimeMillis()
        for (i in 1..30) {
            val f = File(TestSupport.ctx.cacheDir, "page$i.jpg")
            template.copyTo(f, overwrite = true)
            instr.runOnMainSync { vm.onPhotoCaptured(f) }
            waitFor("page $i") { vm.pages.size == i && vm.busy == null }
            peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
        }
        val tCapture = System.currentTimeMillis() - t0
        instr.runOnMainSync { vm.docName = "CSTEST_many" }
        val t1 = System.currentTimeMillis()
        instr.runOnMainSync { vm.exportPdf() }
        waitFor("PDF export", 300_000) { vm.exportResult != null || (vm.message?.contains("failed") == true) }
        val tPdf = System.currentTimeMillis() - t1
        assertNotNull("PDF export failed: ${vm.message}", vm.exportResult)
        peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
        val size = DocumentRepository.list(TestSupport.ctx).firstOrNull { it.name == "CSTEST_many.pdf" }
        log("30 pages of 12 MP: capture total ${tCapture} ms, PDF export ${tPdf} ms, java heap peak ~${peak / 1048576} MB of max ${rt.maxMemory() / 1048576} MB, PDF listed=${size != null}")
        assertEquals(30, vm.pages.size)
        assertNotNull("PDF not listed", size)
    }
}
