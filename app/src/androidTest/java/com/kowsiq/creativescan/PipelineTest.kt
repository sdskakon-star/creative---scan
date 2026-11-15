package com.kowsiq.creativescan

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kowsiq.creativescan.TestSupport.log
import com.kowsiq.creativescan.core.IdCardLayout
import com.kowsiq.creativescan.core.ImageProcessor
import com.kowsiq.creativescan.core.NPoint
import com.kowsiq.creativescan.core.ProcessSettings
import com.kowsiq.creativescan.core.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import kotlin.math.abs

/** Runs the real OpenCV pipeline on synthetic photos with known geometry. */
@RunWith(AndroidJUnit4::class)
class PipelineTest {

    @Before
    fun setUp() {
        assertTrue("OpenCV failed to load", OpenCVLoader.initLocal())
    }

    private fun maxCornerError(a: List<NPoint>, b: List<NPoint>): Float =
        (0 until 4).maxOf { maxOf(abs(a[it].x - b[it].x), abs(a[it].y - b[it].y)) }

    @Test
    fun detectsPaperCorners() {
        val scene = TestSupport.paperScene()
        TestSupport.save("01_paper_scene", scene.bitmap)
        val live = ImageProcessor.analyzeFrame(scene.bitmap, 0.10, false, 480)
        val post = ImageProcessor.analyzeFrame(scene.bitmap, 0.15, true, 800)
        assertNotNull("live detection found nothing", live.corners)
        assertNotNull("post-capture detection found nothing", post.corners)
        val e1 = maxCornerError(live.corners!!, scene.corners)
        val e2 = maxCornerError(post.corners!!, scene.corners)
        log("paper detection: live maxErr=$e1 post maxErr=$e2 area=${post.areaFraction} shadowy=${post.shadowy}")
        assertTrue("live corners off by $e1", e1 < 0.03f)
        assertTrue("post corners off by $e2", e2 < 0.03f)
        assertTrue("paper with a strong shadow should be flagged", post.shadowy)
    }

    @Test
    fun filtersFlattenShadowAndKeepText() {
        val scene = TestSupport.paperScene()
        val original = ImageProcessor.render(
            scene.bitmap, scene.corners, ProcessSettings(ScanFilter.ORIGINAL, sharpness = 0f, autoStraighten = false), 1200
        )
        val origLevels = TestSupport.quadrantPaperLevels(original)
        log("ORIGINAL ${original.width}x${original.height} paper levels=$origLevels")
        TestSupport.save("02_filter_ORIGINAL", original)
        assertTrue("source should visibly have a shadow", origLevels.max() - origLevels.min() > 40)

        for (f in ScanFilter.values()) {
            val out = ImageProcessor.render(scene.bitmap, scene.corners, ProcessSettings(f), 1200)
            val levels = TestSupport.quadrantPaperLevels(out)
            val ink = TestSupport.inkFraction(out)
            val spread = levels.max() - levels.min()
            log("$f ${out.width}x${out.height} paper levels=$levels spread=$spread ink=${"%.4f".format(ink)}")
            TestSupport.save("02_filter_$f", out)
            assertTrue("$f: page must keep the paper aspect", abs(out.width.toDouble() / out.height - 0.78) < 0.2 ||
                abs(out.width.toDouble() / out.height - 1.28) < 0.2)
            assertTrue("$f: text must stay visible (ink=$ink)", ink > 0.004)
            assertTrue("$f: page must not turn black (ink=$ink)", ink < 0.30)
            if (f != ScanFilter.ORIGINAL && f != ScanFilter.PHOTO) {
                assertTrue("$f: shadow not flattened, levels=$levels", spread <= 32)
                assertTrue("$f: paper not white enough, levels=$levels", levels.min() >= 195)
            }
            out.recycle()
        }
        original.recycle()
    }

    @Test
    fun blackAndWhiteIsReallyBinary() {
        val scene = TestSupport.paperScene()
        val out = ImageProcessor.render(scene.bitmap, scene.corners, ProcessSettings(ScanFilter.BLACK_WHITE), 1200)
        val hist = TestSupport.lumaHistogram(out, 0, 0, out.width, out.height)
        val extremes = hist[0] + hist[255] + (1..10).sumOf { hist[it] } + (245..254).sumOf { hist[it] }
        val total = out.width * out.height
        log("B&W extreme fraction=${extremes.toDouble() / total}")
        assertTrue("B&W must be (nearly) only black and white", extremes.toDouble() / total > 0.98)
        // The red stamp is ink too, so it may become black; the page itself must stay mostly white.
        assertTrue(hist[255].toDouble() / total > 0.80)
    }

    @Test
    fun rotationSwapsDimensions() {
        val scene = TestSupport.paperScene()
        val a = ImageProcessor.render(scene.bitmap, scene.corners, ProcessSettings(ScanFilter.ORIGINAL, autoStraighten = false), 800)
        val b = ImageProcessor.render(scene.bitmap, scene.corners, ProcessSettings(ScanFilter.ORIGINAL, autoStraighten = false, rotation = 90), 800)
        assertEquals(a.width, b.height)
        assertEquals(a.height, b.width)
    }

    @Test
    fun idCardIsDetectedAndRectifiedWithoutDistortion() {
        for (variant in 0..1) {
            val scene = TestSupport.cardScene(variant)
            TestSupport.save("03_card_scene_$variant", scene.bitmap)
            val det = ImageProcessor.analyzeFrame(scene.bitmap, 0.06, true, 800)
            assertNotNull("card $variant not detected", det.corners)
            val err = maxCornerError(det.corners!!, scene.corners)
            log("card $variant detection maxErr=$err")
            assertTrue("card $variant corners off by $err", err < 0.03f)

            val s = ProcessSettings(ScanFilter.ENHANCED, sharpness = 0.12f, autoStraighten = false)
            val out = ImageProcessor.render(scene.bitmap, scene.corners, s, 1500, IdCardLayout.CARD_ASPECT)
            TestSupport.save("03_card_rectified_$variant", out)
            val aspect = out.width.toDouble() / out.height
            log("card $variant rectified ${out.width}x${out.height} aspect=$aspect (target ${IdCardLayout.CARD_ASPECT})")
            assertEquals(IdCardLayout.CARD_ASPECT, aspect, 0.03)

            val stamp = TestSupport.avgColor(out, (out.width * TestSupport.STAMP_U).toInt(), (out.height * TestSupport.STAMP_V).toInt(), 12)
            log("card $variant stamp colour after ENHANCED = ${stamp.toList()}")
            assertTrue("red stamp lost its colour: ${stamp.toList()}", stamp[0] > 150 && stamp[1] < 110 && stamp[2] < 110)
            val photo = TestSupport.avgColor(out, (out.width * TestSupport.PHOTO_U).toInt(), (out.height * TestSupport.PHOTO_V).toInt(), 20)
            assertTrue("photo area too dark: ${photo.toList()}", photo.min() > 190)
            out.recycle()
        }
    }

    @Test
    fun largeImageRendersWithinMemoryLimits() {
        val big = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
        big.eraseColor(0xFFF0F0F0.toInt())
        val t0 = System.currentTimeMillis()
        for (f in listOf(ScanFilter.ENHANCED, ScanFilter.DOCUMENT_CLEAN, ScanFilter.BLACK_WHITE)) {
            val out = ImageProcessor.render(big, ImageProcessor.defaultCorners(0.02f), ProcessSettings(f), 3508)
            assertTrue(out.width > 1000)
            out.recycle()
        }
        log("3 full-size renders of a 12 MP image took ${System.currentTimeMillis() - t0} ms")
    }
}
