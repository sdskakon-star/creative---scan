package com.kowsiq.creativescan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kowsiq.creativescan.core.ImageProcessor
import com.kowsiq.creativescan.core.ProcessSettings
import com.kowsiq.creativescan.core.ScanFilter
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Runs the real pipeline over realistic test photos (see assets/scenes, with ground truth) and writes
 * full-resolution results so they can be measured off-device (OCR, edge marks, shadow, creases).
 */
@RunWith(AndroidJUnit4::class)
class QualityRunTest {
    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        org.junit.Assert.assertTrue(OpenCVLoader.initLocal())
    }

    @Test
    fun processAllScenes() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = JSONArray(assets.open("scenes/index.json").bufferedReader().readText())
        val dir = File(TestSupport.outDir, "quality").apply { mkdirs() }
        val report = File(dir, "detect.txt")
        report.writeText("")
        for (i in 0 until names.length()) {
            val n = names.getString(i)
            val src: Bitmap = assets.open("scenes/$n.jpg").use { BitmapFactory.decodeStream(it) }
            val t0 = System.currentTimeMillis()
            val det = ImageProcessor.analyzeFrame(src, 0.15, true, 800)
            val user = n.startsWith("u")   // real user photos are already cropped: use the full frame
            val corners = if (user) ImageProcessor.defaultCorners(0f) else (det.corners ?: ImageProcessor.defaultCorners(0.02f))
            report.appendText("$n detected=${det.corners != null} shadowy=${det.shadowy} area=${det.areaFraction} corners=${corners.joinToString(";") { "%.4f,%.4f".format(it.x, it.y) }}\n")
            val sharp = ImageProcessor.sharpnessScore(src, corners)
            report.appendText("$n sharpness=${"%.2f".format(sharp)}\n")
            if (user) Unit else if (n == "s10_blur") org.junit.Assert.assertTrue("blurry photo not flagged ($sharp)", sharp < ImageProcessor.BLUR_LIMIT)
            else org.junit.Assert.assertTrue("$n wrongly flagged as blurry ($sharp)", sharp >= ImageProcessor.BLUR_LIMIT)
            for (f in if (user) ScanFilter.values().toList() else listOf(ScanFilter.ENHANCED, ScanFilter.DOCUMENT_CLEAN, ScanFilter.BLACK_WHITE, ScanFilter.ORIGINAL)) {
                val out = ImageProcessor.render(src, corners, ProcessSettings(f), 2400)
                val file = File(dir, "${n}__$f." + if (f == ScanFilter.BLACK_WHITE) "png" else "jpg")
                file.outputStream().use {
                    if (f == ScanFilter.BLACK_WHITE) out.compress(Bitmap.CompressFormat.PNG, 100, it)
                    else out.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
                out.recycle()
            }
            report.appendText("$n time_ms=${System.currentTimeMillis() - t0}\n")
            src.recycle()
        }
    }
}
