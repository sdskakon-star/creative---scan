package com.kowsiq.creativescan.core

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * All image work of the app: document detection, perspective correction and the
 * enhancement filters. Everything runs locally through OpenCV.
 */
object ImageProcessor {

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    fun defaultCorners(inset: Float = 0.02f): List<NPoint> = listOf(
        NPoint(inset, inset), NPoint(1f - inset, inset),
        NPoint(1f - inset, 1f - inset), NPoint(inset, 1f - inset)
    )

    /**
     * Finds the document quadrilateral in [src]. Used for the live preview (cheap, strict)
     * and after capture (larger working size, with a fallback shape search).
     */
    fun analyzeFrame(
        src: Bitmap,
        minAreaFraction: Double = 0.10,
        allowFallback: Boolean = false,
        workSize: Int = 480
    ): LiveResult {
        val scaled = scaleToMax(src, workSize)
        val rgba = Mat()
        Utils.bitmapToMat(scaled, rgba)
        if (scaled !== src) scaled.recycle()

        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        val quad = findQuad(gray, minAreaFraction, allowFallback)
        val result = if (quad == null) {
            LiveResult(null, 0.0, false)
        } else {
            val polygon = MatOfPoint(*quad.toTypedArray())
            val area = try { Imgproc.contourArea(polygon) } finally { polygon.release() }
            LiveResult(
                corners = quad.map { NPoint((it.x / w).toFloat(), (it.y / h).toFloat()) },
                areaFraction = area / (w * h),
                shadowy = isShadowy(gray, quad)
            )
        }
        gray.release()
        return result
    }

    private fun findQuad(gray: Mat, minFraction: Double, fallback: Boolean): List<Point>? {
        val imgArea = gray.rows().toDouble() * gray.cols()
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val edges = Mat()
        val otsu = Mat()
        val otsuInv = Mat()
        try {
            val mean = Core.mean(gray).`val`[0]
            Imgproc.Canny(gray, edges, max(20.0, 0.66 * mean), min(255.0, max(60.0, 1.33 * mean)))
            Imgproc.dilate(edges, edges, kernel3)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel3, Point(-1.0, -1.0), 2)

            Imgproc.threshold(gray, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_CLOSE, kernel5)
            Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_OPEN, kernel5)
            Core.bitwise_not(otsu, otsuInv)

            var best: List<Point>? = null
            var bestArea = 0.0
            for (mask in listOf(edges, otsu, otsuInv)) {
                val contourInput = mask.clone()
                val hierarchy = Mat()
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.sortByDescending { Imgproc.contourArea(it) }
                    for (cnt in contours.take(8)) {
                        val area = Imgproc.contourArea(cnt)
                        if (area < minFraction * imgArea) break
                        if (area > 0.99 * imgArea) continue
                        val c2f = MatOfPoint2f(*cnt.toArray())
                        val approx = MatOfPoint2f()
                        try {
                            val peri = Imgproc.arcLength(c2f, true)
                            Imgproc.approxPolyDP(c2f, approx, 0.018 * peri, true)
                            val pts = approx.toArray()
                            if (pts.size == 4) {
                                val polygon = MatOfPoint(*pts)
                                val convex = try { Imgproc.isContourConvex(polygon) } finally { polygon.release() }
                                if (convex) {
                                    val ordered = orderPoints(pts.toList())
                                    if (isPlausible(ordered) && area > bestArea) {
                                        best = ordered
                                        bestArea = area
                                    }
                                }
                            }
                        } finally {
                            c2f.release()
                            approx.release()
                        }
                    }
                } finally {
                    contourInput.release()
                    hierarchy.release()
                    contours.forEach { it.release() }
                }
            }

            if (best == null && fallback) {
                val contourInput = otsu.clone()
                val hierarchy = Mat()
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
                    if (largest != null && Imgproc.contourArea(largest) > 0.25 * imgArea) {
                        val points = MatOfPoint2f(*largest.toArray())
                        try {
                            val rect = Imgproc.minAreaRect(points)
                            val box = Array(4) { Point() }
                            rect.points(box)
                            val ordered = orderPoints(box.toList())
                            if (isPlausible(ordered)) best = ordered
                        } finally {
                            points.release()
                        }
                    }
                } finally {
                    contourInput.release()
                    hierarchy.release()
                    contours.forEach { it.release() }
                }
            }
            return best
        } finally {
            kernel3.release()
            kernel5.release()
            edges.release()
            otsu.release()
            otsuInv.release()
        }
    }

    private fun isShadowy(gray: Mat, quad: List<Point>): Boolean {
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val polygon = MatOfPoint(*quad.toTypedArray())
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
        val blur = Mat()
        try {
            Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0))
            Imgproc.erode(mask, mask, kernel)
            Imgproc.GaussianBlur(gray, blur, Size(0.0, 0.0), 18.0)
            val mm = Core.minMaxLoc(blur, mask)
            return mm.maxVal > 0 && mm.minVal / mm.maxVal < 0.55
        } finally {
            mask.release()
            polygon.release()
            kernel.release()
            blur.release()
        }
    }

    /** Orders four points as top-left, top-right, bottom-right, bottom-left. */
    fun orderPoints(p: List<Point>): List<Point> {
        val sum = p.map { it.x + it.y }
        val diff = p.map { it.y - it.x }
        val tl = p[sum.indexOf(sum.min())]
        val br = p[sum.indexOf(sum.max())]
        val tr = p[diff.indexOf(diff.min())]
        val bl = p[diff.indexOf(diff.max())]
        return listOf(tl, tr, br, bl)
    }

    private fun isPlausible(p: List<Point>): Boolean {
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]
            val b = p[i]
            val c = p[(i + 1) % 4]
            val ux = a.x - b.x; val uy = a.y - b.y
            val vx = c.x - b.x; val vy = c.y - b.y
            val lu = sqrt(ux * ux + uy * uy)
            val lv = sqrt(vx * vx + vy * vy)
            if (lu < 5 || lv < 5) return false
            val cos = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)
            val angle = Math.toDegrees(acos(cos))
            if (angle < 55 || angle > 125) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // Rendering pipeline: perspective -> straighten -> filter -> adjust -> rotate
    // ------------------------------------------------------------------

    /**
     * Renders the final page. [maxSide] limits the output's longer side (0 = native size,
     * still capped at [HARD_CAP]); the image is never upscaled.
     */
    fun render(
        src: Bitmap,
        corners: List<NPoint>,
        s: ProcessSettings,
        maxSide: Int = 0,
        targetAspectRatio: Double? = null
    ): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        var m = warp(rgb, corners, maxSide, targetAspectRatio)
        rgb.release()

        if (s.autoStraighten && s.filter != ScanFilter.PHOTO && s.filter != ScanFilter.ORIGINAL) {
            m = deskew(m)
        }

        m = when (s.filter) {
            ScanFilter.ORIGINAL -> adjustAndSharpen(m, s)
            ScanFilter.ENHANCED -> adjustAndSharpen(filterEnhanced(m), s)
            ScanFilter.GRAYSCALE -> adjustAndSharpen(filterGrayscale(m), s)
            ScanFilter.BLACK_WHITE -> filterBlackWhite(m, s)
            ScanFilter.DOCUMENT_CLEAN -> adjustAndSharpen(filterDocumentClean(m), s)
            ScanFilter.PHOTO -> adjustAndSharpen(filterPhoto(m), s)
        }

        m = rotate(m, s.rotation)
        val bmp = toBitmap(m)
        m.release()
        return bmp
    }

    const val HARD_CAP = 4600

    private fun warp(src: Mat, corners: List<NPoint>, maxSide: Int, targetAspectRatio: Double? = null): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        val p = corners.map { Point(it.x * w, it.y * h) }
        val top = dist(p[0], p[1]); val bottom = dist(p[3], p[2])
        val left = dist(p[0], p[3]); val right = dist(p[1], p[2])
        var dw = max(top, bottom)
        var dh = max(left, right)
        // ISO/IEC 7810 ID-1 cards (most bank, identity and access cards) use 85.60 x 53.98 mm.
        // The optional ratio is used only by the dedicated two-sided card-copy workflow.
        if (targetAspectRatio != null && targetAspectRatio > 0.5) {
            // Keep the card's own orientation: a card photographed upright (taller than wide)
            // gets the inverse ratio so it is not stretched. The caller rotates it afterwards.
            val ratio = if (dw >= dh) targetAspectRatio else 1.0 / targetAspectRatio
            if (dw / max(dh, 1.0) >= ratio) dh = dw / ratio
            else dw = dh * ratio
        }
        val limit = if (maxSide > 0) min(maxSide, HARD_CAP) else HARD_CAP
        val longer = max(dw, dh)
        if (longer > limit) {
            val k = limit / longer
            dw *= k; dh *= k
        }
        val outW = max(16, dw.roundToInt())
        val outH = max(16, dh.roundToInt())

        val from = MatOfPoint2f(p[0], p[1], p[2], p[3])
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0)
        )
        val transform = Imgproc.getPerspectiveTransform(from, to)
        val dst = Mat()
        try {
            Imgproc.warpPerspective(
                src, dst, transform, Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
            )
            return dst
        } catch (e: Exception) {
            dst.release()
            throw e
        } finally {
            from.release()
            to.release()
            transform.release()
        }
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    // ---------------- straightening (small residual skew) ----------------

    private fun deskew(m: Mat): Mat {
        val f = min(1.0, 500.0 / max(m.cols(), m.rows()))
        val small = Mat()
        Imgproc.resize(m, small, Size(), f, f, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        small.release()
        val bin = Mat()
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        gray.release()
        if (Core.countNonZero(bin) < 0.003 * bin.total()) {
            bin.release(); return m
        }

        val center = Point(bin.cols() / 2.0, bin.rows() / 2.0)
        var bestAngle = 0.0
        var bestScore = -1.0
        var zeroScore = 0.0
        var a = -3.0
        while (a <= 3.001) {
            val r = Imgproc.getRotationMatrix2D(center, a, 1.0)
            val rot = Mat()
            Imgproc.warpAffine(bin, rot, r, bin.size())
            val profile = Mat()
            Core.reduce(rot, profile, 1, Core.REDUCE_SUM, CvType.CV_32F)
            val mean = MatOfDouble(); val std = MatOfDouble()
            Core.meanStdDev(profile, mean, std)
            val score = std.toArray()[0]
            if (abs(a) < 1e-6) zeroScore = score
            if (score > bestScore) { bestScore = score; bestAngle = a }
            r.release(); rot.release(); profile.release(); mean.release(); std.release()
            a += 0.25
        }
        bin.release()

        if (abs(bestAngle) < 0.4 || bestScore < zeroScore * 1.03) return m
        val r = Imgproc.getRotationMatrix2D(Point(m.cols() / 2.0, m.rows() / 2.0), bestAngle, 1.0)
        val out = Mat()
        Imgproc.warpAffine(m, out, r, m.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        r.release(); m.release()
        return out
    }

    // ---------------- filters ----------------

    private fun filterEnhanced(rgb: Mat): Mat {
        whiteBalance(rgb, 1.12)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        rgb.release()
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val norm = normalizeLuminance(ch[0], 0.85)
        val eq = clahe(norm, 1.6)
        norm.release()
        eq.copyTo(ch[0]); eq.release()
        neutralizePaper(ch, 190.0, 8.0)
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return denoise(out, 22.0)
    }

    private fun filterGrayscale(rgb: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        rgb.release()
        val norm = normalizeLuminance(gray, 0.9)
        gray.release()
        val eq = clahe(norm, 1.3)
        norm.release()
        val out = Mat()
        Imgproc.cvtColor(eq, out, Imgproc.COLOR_GRAY2RGB)
        eq.release()
        return out
    }

    private fun filterBlackWhite(rgb: Mat, s: ProcessSettings): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        rgb.release()
        adjustBrightnessContrast(gray, s.brightness, s.contrast)
        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
        val norm = normalizeLuminance(gray, 1.0)
        gray.release()
        val block = oddAtLeast(min(norm.cols(), norm.rows()) / 35, 21)
        val bin = Mat()
        Imgproc.adaptiveThreshold(
            norm, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, block, 10.0
        )
        norm.release()
        val out = Mat()
        Imgproc.cvtColor(bin, out, Imgproc.COLOR_GRAY2RGB)
        bin.release()
        return out
    }

    private fun filterDocumentClean(rgb: Mat): Mat {
        whiteBalance(rgb, 1.12)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        rgb.release()
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val norm = normalizeLuminance(ch[0], 1.0)
        // Levels: push paper to white, keep soft anti-aliased strokes.
        val leveled = Mat()
        norm.convertTo(leveled, -1, 255.0 / 185.0, -40.0 * 255.0 / 185.0)
        norm.release()
        leveled.copyTo(ch[0]); leveled.release()
        // Remove paper tint only where chroma is tiny; coloured stamps/ink keep their colour.
        neutralizePaper(ch, 200.0, 12.0)
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return denoise(out, 18.0)
    }

    private fun filterPhoto(rgb: Mat): Mat {
        whiteBalance(rgb, 1.08)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        rgb.release()
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val eq = clahe(ch[0], 1.2)
        eq.copyTo(ch[0]); eq.release()
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return out
    }

    // ---------------- building blocks ----------------

    /**
     * Illumination normalisation: estimates the paper background on a small copy
     * (closing removes thin text strokes, large dark regions such as photos survive),
     * then divides the luminance by it. The background is floored so dark photographs
     * are not blown out.
     */
    private fun normalizeLuminance(l: Mat, strength: Double): Mat {
        val bg = estimateBackground(l)
        val l32 = Mat(); val bg32 = Mat()
        l.convertTo(l32, CvType.CV_32F)
        bg.convertTo(bg32, CvType.CV_32F)
        bg.release()
        Core.max(bg32, Scalar(150.0), bg32)
        val res32 = Mat()
        Core.divide(l32, bg32, res32, 245.0)
        l32.release(); bg32.release()
        val res = Mat()
        res32.convertTo(res, CvType.CV_8U)
        res32.release()
        val out = Mat()
        Core.addWeighted(res, strength, l, 1.0 - strength, 0.0, out)
        res.release()
        return out
    }

    private fun estimateBackground(gray: Mat): Mat {
        val f = min(1.0, 400.0 / max(gray.cols(), gray.rows()))
        val small = Mat()
        Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.GaussianBlur(small, small, Size(15.0, 15.0), 0.0)
        val bg = Mat()
        Imgproc.resize(small, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        return bg
    }

    private fun clahe(l: Mat, clip: Double): Mat {
        val c = Imgproc.createCLAHE(clip, Size(8.0, 8.0))
        val out = Mat()
        c.apply(l, out)
        return out
    }

    /** Gray-world white balance with limited gain, in place. */
    private fun whiteBalance(rgb: Mat, maxGain: Double) {
        val mean = Core.mean(rgb).`val`
        val target = (mean[0] + mean[1] + mean[2]) / 3.0
        val ch = ArrayList<Mat>()
        Core.split(rgb, ch)
        for (i in 0 until 3) {
            val gain = (target / max(mean[i], 1.0)).coerceIn(1.0 / maxGain, maxGain)
            ch[i].convertTo(ch[i], -1, gain, 0.0)
        }
        Core.merge(ch, rgb)
        ch.forEach { it.release() }
    }

    /** Sets a/b to neutral where the pixel is bright and nearly colourless (paper). */
    private fun neutralizePaper(lab: List<Mat>, lMin: Double, tol: Double) {
        val mL = Mat(); val mA = Mat(); val mB = Mat()
        Core.inRange(lab[0], Scalar(lMin), Scalar(255.0), mL)
        Core.inRange(lab[1], Scalar(128.0 - tol), Scalar(128.0 + tol), mA)
        Core.inRange(lab[2], Scalar(128.0 - tol), Scalar(128.0 + tol), mB)
        Core.bitwise_and(mL, mA, mL)
        Core.bitwise_and(mL, mB, mL)
        lab[1].setTo(Scalar(128.0), mL)
        lab[2].setTo(Scalar(128.0), mL)
        mL.release(); mA.release(); mB.release()
    }

    private fun denoise(rgb: Mat, sigmaColor: Double): Mat {
        val out = Mat()
        Imgproc.bilateralFilter(rgb, out, 5, sigmaColor, 3.0)
        rgb.release()
        return out
    }

    private fun adjustBrightnessContrast(m: Mat, brightness: Float, contrast: Float) {
        if (brightness == 0f && contrast == 1f) return
        val alpha = contrast.toDouble()
        val beta = brightness * 1.27 + 128.0 * (1.0 - alpha)
        m.convertTo(m, -1, alpha, beta)
    }

    private fun adjustAndSharpen(m: Mat, s: ProcessSettings): Mat {
        adjustBrightnessContrast(m, s.brightness, s.contrast)
        if (s.sharpness > 0f) {
            val amount = s.sharpness * 1.2
            val blur = Mat()
            Imgproc.GaussianBlur(m, blur, Size(0.0, 0.0), 1.4)
            Core.addWeighted(m, 1.0 + amount, blur, -amount, 0.0, m)
            blur.release()
        }
        return m
    }

    private fun rotate(m: Mat, degrees: Int): Mat {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return m
        }
        val out = Mat()
        Core.rotate(m, out, code)
        m.release()
        return out
    }

    private fun toBitmap(rgb: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }

    private fun oddAtLeast(v: Int, minimum: Int): Int {
        val x = max(v, minimum)
        return if (x % 2 == 0) x + 1 else x
    }

    private fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val longer = max(src.width, src.height)
        if (longer <= maxSide) return src
        val k = maxSide.toFloat() / longer
        return Bitmap.createScaledBitmap(
            src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true
        )
    }
}
