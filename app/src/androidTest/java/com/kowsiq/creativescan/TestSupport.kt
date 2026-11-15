package com.kowsiq.creativescan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.kowsiq.creativescan.core.NPoint
import java.io.File
import java.util.Random
import kotlin.math.max

/** Synthetic camera frames with known ground truth, plus helpers to measure and save results. */
object TestSupport {
    const val TAG = "CSTEST"

    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val outDir: File
        get() = (ctx.getExternalFilesDir("out") ?: File(ctx.filesDir, "out")).apply { mkdirs() }

    class Scene(val bitmap: Bitmap, val corners: List<NPoint>)

    private fun desk(w: Int, h: Int, base: Int, seed: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(base)
        val rnd = Random(seed)
        val p = Paint()
        repeat(1800) {
            p.color = Color.argb(30, 30 + rnd.nextInt(60), 25 + rnd.nextInt(50), 20 + rnd.nextInt(40))
            val x = rnd.nextInt(w).toFloat(); val y = rnd.nextInt(h).toFloat()
            c.drawLine(x, y, x + rnd.nextInt(90) - 20, y + rnd.nextInt(8) - 4, p)
        }
        return bmp
    }

    private fun norm(q: FloatArray, w: Int, h: Int) =
        (0 until 4).map { NPoint(q[it * 2] / w, q[it * 2 + 1] / h) }

    /** A white page photographed at an angle on a dark desk, with printed text and a diagonal shadow. */
    fun paperScene(): Scene {
        val w = 1600; val h = 1200
        val bmp = desk(w, h, Color.rgb(74, 62, 52), 7)
        val c = Canvas(bmp)
        val q = floatArrayOf(330f, 170f, 1250f, 230f, 1190f, 1050f, 270f, 950f)
        val path = Path().apply {
            moveTo(q[0], q[1]); lineTo(q[2], q[3]); lineTo(q[4], q[5]); lineTo(q[6], q[7]); close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 244, 238) })
        c.save()
        c.clipPath(path)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 30); textSize = 30f }
        for (i in 0 until 22) c.drawText("Invoice line $i   The quick brown fox jumps over", 380f, 270f + i * 38f, text)
        val stamp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 35, 45); style = Paint.Style.STROKE; strokeWidth = 8f
        }
        c.drawCircle(980f, 760f, 80f, stamp)
        val shadow = Paint().apply {
            shader = LinearGradient(300f, 200f, 1250f, 1000f, Color.argb(0, 0, 0, 0), Color.argb(115, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shadow)
        c.restore()
        return Scene(bmp, norm(q, w, h))
    }

    const val STAMP_U = 700f / 856f
    const val STAMP_V = 420f / 540f
    const val PHOTO_U = 170f / 856f
    const val PHOTO_V = 300f / 540f

    /** A blue ID card with a photo box and a red stamp, photographed with perspective. */
    fun cardScene(variant: Int): Scene {
        val w = 1600; val h = 1200
        val bmp = desk(w, h, Color.rgb(118, 118, 124), 11L + variant)
        val card = Bitmap.createBitmap(856, 540, Bitmap.Config.ARGB_8888)
        val cc = Canvas(card)
        cc.drawRect(0f, 0f, 856f, 540f, Paint().apply {
            shader = LinearGradient(0f, 0f, 856f, 540f, Color.rgb(0, 70, 150), Color.rgb(20, 150, 205), Shader.TileMode.CLAMP)
        })
        cc.drawRect(40f, 150f, 300f, 450f, Paint().apply { color = Color.rgb(245, 245, 245) })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f }
        cc.drawText(if (variant == 0) "IDENTITY CARD" else "ADDRESS / SIGNATURE", 40f, 80f, t)
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 15, 25); textSize = 34f }
        for (i in 0 until 5) cc.drawText("Name  Field ${i + 1}  0123456789", 340f, 190f + i * 50f, dark)
        cc.drawCircle(700f, 420f, 55f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 30, 40) })

        val q = if (variant == 0) floatArrayOf(300f, 260f, 1180f, 330f, 1130f, 820f, 240f, 740f)
        else floatArrayOf(380f, 300f, 1250f, 250f, 1300f, 760f, 330f, 830f)
        val m = Matrix()
        m.setPolyToPoly(floatArrayOf(0f, 0f, 856f, 0f, 856f, 540f, 0f, 540f), 0, q, 0, 4)
        val c = Canvas(bmp)
        c.drawBitmap(card, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        card.recycle()
        return Scene(bmp, norm(q, w, h))
    }

    // ---------- measurements ----------

    fun luma(p: Int): Int = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000

    fun lumaHistogram(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val hist = IntArray(256)
        val row = IntArray(x1 - x0)
        for (y in y0 until y1) {
            b.getPixels(row, 0, x1 - x0, x0, y, x1 - x0, 1)
            for (p in row) hist[luma(p)]++
        }
        return hist
    }

    fun percentile(hist: IntArray, q: Double): Int {
        val total = hist.sum()
        var acc = 0
        for (i in 0..255) { acc += hist[i]; if (acc >= total * q) return i }
        return 255
    }

    /** 90th-percentile luminance (the "paper level") of each quadrant. */
    fun quadrantPaperLevels(b: Bitmap): List<Int> {
        val w = b.width; val h = b.height
        return listOf(
            percentile(lumaHistogram(b, 0, 0, w / 2, h / 2), 0.9),
            percentile(lumaHistogram(b, w / 2, 0, w, h / 2), 0.9),
            percentile(lumaHistogram(b, 0, h / 2, w / 2, h), 0.9),
            percentile(lumaHistogram(b, w / 2, h / 2, w, h), 0.9)
        )
    }

    fun inkFraction(b: Bitmap, threshold: Int = 110): Double {
        val hist = lumaHistogram(b, 0, 0, b.width, b.height)
        var dark = 0
        for (i in 0 until threshold) dark += hist[i]
        return dark.toDouble() / (b.width.toLong() * b.height)
    }

    /** Bounding box of pixels that are not (nearly) white: left, top, right(exclusive), bottom(exclusive). */
    fun contentBounds(b: Bitmap, whiteLimit: Int = 235): IntArray {
        var l = b.width; var t = b.height; var r = -1; var bt = -1
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in 0 until b.width) {
                val p = row[x]
                if (((p shr 16) and 255) < whiteLimit || ((p shr 8) and 255) < whiteLimit || (p and 255) < whiteLimit) {
                    if (x < l) l = x; if (x > r) r = x
                    if (y < t) t = y; if (y > bt) bt = y
                }
            }
        }
        return intArrayOf(l, t, r + 1, bt + 1)
    }

    fun avgColor(b: Bitmap, cx: Int, cy: Int, rad: Int): IntArray {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (y in max(0, cy - rad)..minOf(b.height - 1, cy + rad)) {
            for (x in max(0, cx - rad)..minOf(b.width - 1, cx + rad)) {
                val p = b.getPixel(x, y)
                r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; n++
            }
        }
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    // ---------- output ----------

    fun log(msg: String) {
        Log.i(TAG, msg)
        File(outDir, "metrics.txt").appendText(msg + "\n")
    }

    /** Saves a downscaled JPEG that is small enough to review. */
    fun save(name: String, b: Bitmap, maxSide: Int = 900) {
        val k = minOf(1f, maxSide.toFloat() / max(b.width, b.height))
        val s = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        File(outDir, "$name.jpg").outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (s !== b) s.recycle()
    }

    fun saveJpegFile(b: Bitmap, f: File, q: Int = 95) {
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, q, it) }
    }
}
