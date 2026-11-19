package com.kowsiq.creativescan.core

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
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
        workSize: Int = 480,
        cardOnly: Boolean = false
    ): LiveResult {
        val scaled = scaleToMax(src, workSize)
        val rgba = Mat()
        Utils.bitmapToMat(scaled, rgba)
        if (scaled !== src) scaled.recycle()

        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        // ID cards often have a luminance close to the table they lie on while differing clearly
        // in colour, so card detection also looks for edges in each colour channel.
        val channels = ArrayList<Mat>()
        if (cardOnly) {
            val split = ArrayList<Mat>()
            Core.split(rgba, split)
            for (i in split.indices) {
                if (i < 3) {
                    Imgproc.GaussianBlur(split[i], split[i], Size(5.0, 5.0), 0.0)
                    channels.add(split[i])
                } else {
                    split[i].release()
                }
            }
        }
        rgba.release()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        val quad = try {
            findQuad(gray, minAreaFraction, allowFallback, channels, cardOnly)
        } finally {
            channels.forEach { it.release() }
        }
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

    private fun findQuad(
        gray: Mat,
        minFraction: Double,
        fallback: Boolean,
        extraChannels: List<Mat>,
        cardOnly: Boolean
    ): List<Point>? {
        val imgArea = gray.rows().toDouble() * gray.cols()
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val edges = Mat()
        val otsu = Mat()
        val otsuInv = Mat()
        try {
            for ((index, source) in (listOf(gray) + extraChannels).withIndex()) {
                val mean = Core.mean(source).`val`[0]
                val e = if (index == 0) edges else Mat()
                Imgproc.Canny(source, e, max(20.0, 0.66 * mean), min(255.0, max(60.0, 1.33 * mean)))
                if (index > 0) {
                    Core.bitwise_or(edges, e, edges)
                    e.release()
                }
            }
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
                                    if (isPlausible(ordered) && (!cardOnly || isCardShaped(ordered)) && area > bestArea) {
                                        best = ordered
                                        bestArea = area
                                    }
                                }
                            } else if (cardOnly) {
                                // Noise touching the outline can add extra corners; the convex hull
                                // of a card outline is still a clean four-sided shape.
                                val hullQuad = hullToQuad(cnt)
                                if (hullQuad != null && isPlausible(hullQuad) && isCardShaped(hullQuad) && area > bestArea) {
                                    best = hullQuad
                                    bestArea = area
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
                    // A region that fills (almost) the whole frame is a blank/uniform image, not a document.
                    val largestArea = if (largest != null) Imgproc.contourArea(largest) else 0.0
                    if (largest != null && largestArea > (if (cardOnly) minFraction else 0.25) * imgArea &&
                        largestArea < 0.97 * imgArea
                    ) {
                        val points = MatOfPoint2f(*largest.toArray())
                        try {
                            val rect = Imgproc.minAreaRect(points)
                            val box = Array(4) { Point() }
                            rect.points(box)
                            val ordered = orderPoints(box.toList())
                            if (isPlausible(ordered) && (!cardOnly || isCardShaped(ordered))) best = ordered
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

    /** High-quality downscale (area averaging) so small printed text stays crisp. */
    fun resizeArea(src: Bitmap, width: Int, height: Int): Bitmap {
        val m = Mat()
        Utils.bitmapToMat(src, m)
        val d = Mat()
        Imgproc.resize(m, d, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        m.release()
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(d, out)
        d.release()
        return out
    }

    /** ID-1 cards are 85.6 x 53.98 mm (1.586); 1.25 to 1.95 also tolerates perspective and ID-3 pages. */
    private fun isCardShaped(p: List<Point>): Boolean {
        val w = (dist(p[0], p[1]) + dist(p[3], p[2])) / 2
        val h = (dist(p[0], p[3]) + dist(p[1], p[2])) / 2
        if (w < 1 || h < 1) return false
        return max(w, h) / min(w, h) in 1.25..1.95
    }

    private fun hullToQuad(contour: MatOfPoint): List<Point>? {
        val hullIdx = MatOfInt()
        val hull = MatOfPoint()
        val hull2f = MatOfPoint2f()
        val approx = MatOfPoint2f()
        try {
            Imgproc.convexHull(contour, hullIdx)
            val idx = hullIdx.toArray()
            val src = contour.toArray()
            if (idx.size < 4) return null
            hull.fromList(idx.map { src[it] })
            hull2f.fromList(hull.toList())
            Imgproc.approxPolyDP(hull2f, approx, 0.03 * Imgproc.arcLength(hull2f, true), true)
            val pts = approx.toArray()
            return if (pts.size == 4) orderPoints(pts.toList()) else null
        } finally {
            hullIdx.release(); hull.release(); hull2f.release(); approx.release()
        }
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

    /**
     * Sharpness of the rectified page, independent of how contrasty or colourful the print is:
     * the 99.5th-percentile edge strength after a light blur (sigma 1) divided by the same after a
     * stronger blur (sigma 3). Real sharp edges lose a lot of gradient when blurred more (ratio ~3);
     * edges that are already smeared barely change (ratio ~2). The weaker of the horizontal and
     * vertical directions counts, so motion blur in one direction is caught.
     * Measured: sharp text/leaflet photos 2.45-3.4, the heavily blurred test photo 1.99.
     */
    fun sharpnessScore(src: Bitmap, corners: List<NPoint>): Double {
        val page = render(src, corners, ProcessSettings(ScanFilter.ORIGINAL, sharpness = 0f, autoStraighten = false), 1200)
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        page.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val n = gray.rows() * gray.cols()
        fun edgeP(sigma: Double, dx: Int, dy: Int): Double {
            val b = Mat(); Imgproc.GaussianBlur(gray, b, Size(0.0, 0.0), sigma)
            val g = Mat(); Imgproc.Sobel(b, g, CvType.CV_32F, dx, dy, 3)
            b.release()
            Core.absdiff(g, Scalar(0.0), g)
            val f = FloatArray(n); g.get(0, 0, f); g.release()
            f.sort()
            return f[(n * 0.995).toInt().coerceAtMost(n - 1)].toDouble()
        }
        val rx = edgeP(1.0, 1, 0) / max(edgeP(3.0, 1, 0), 1e-3)
        val ry = edgeP(1.0, 0, 1) / max(edgeP(3.0, 0, 1), 1e-3)
        gray.release()
        return min(rx, ry)
    }

    const val BLUR_LIMIT = 2.25

    const val HARD_CAP = 4600
    private const val EDGE_INSET = 0.993
    private const val FOLD_THRESHOLD = 18.0

    private fun warp(src: Mat, corners: List<NPoint>, maxSide: Int, targetAspectRatio: Double? = null): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        val raw = corners.map { Point(it.x * w, it.y * h) }
        // Detected edges are a few pixels off, which shows as a thin desk-coloured line along the page border.
        // Pulling the corners in by ~0.7 % of their distance to the centre removes it.
        val cx = raw.sumOf { it.x } / 4.0; val cy = raw.sumOf { it.y } / 4.0
        val fullFrame = corners.all { (it.x < 0.002f || it.x > 0.998f) && (it.y < 0.002f || it.y > 0.998f) }
        val k = if (fullFrame) 1.0 else EDGE_INSET   // an already composed sheet (e.g. the A4 ID page) is never trimmed
        val p = raw.map { Point(cx + (it.x - cx) * k, cy + (it.y - cy) * k) }
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
            return if (!fullFrame && targetAspectRatio == null) trimDeskStrips(dst) else dst
        } catch (e: Exception) {
            dst.release()
            throw e
        } finally {
            from.release()
            to.release()
            transform.release()
        }
    }

    /**
     * If the detected quad still includes a strip of dark desk along a side (the whole side, edge to edge,
     * much darker than the paper), crop it off. A printed bar never spans the full page height/width, so
     * only real desk strips match. Returns [m] itself when nothing has to be cut.
     */
    private fun trimDeskStrips(m: Mat): Mat {
        val g = Mat(); Imgproc.cvtColor(m, g, Imgproc.COLOR_RGB2GRAY)
        val w = g.cols(); val h = g.rows()
        val small = Mat(); Imgproc.resize(g, small, Size(), 0.25, 0.25, Imgproc.INTER_AREA); g.release()
        val sw = small.cols(); val sh = small.rows()
        val buf = ByteArray(sw * sh); small.get(0, 0, buf); small.release()
        fun v(x: Int, y: Int) = buf[y * sw + x].toInt() and 255
        val all = FloatArray(sw * sh) { (buf[it].toInt() and 255).toFloat() }
        val paper = percentile(all, 0.6)
        fun colMed(x: Int): Float {
            val a = FloatArray(sh - 2 * (sh / 10)) { v(x, it + sh / 10).toFloat() }; a.sort(); return a[a.size / 2]
        }
        fun rowMed(y: Int): Float {
            val a = FloatArray(sw - 2 * (sw / 10)) { v(it + sw / 10, y).toFloat() }; a.sort(); return a[a.size / 2]
        }
        val lim = 0.6f * paper
        val maxX = (sw * 0.08).toInt(); val maxY = (sh * 0.08).toInt()
        var l = 0; while (l < maxX && colMed(l) < lim) l++
        var r = 0; while (r < maxX && colMed(sw - 1 - r) < lim) r++
        var t = 0; while (t < maxY && rowMed(t) < lim) t++
        var b = 0; while (b < maxY && rowMed(sh - 1 - b) < lim) b++
        if (l + r + t + b == 0) return m
        fun px(n: Int, k: Int) = if (n == 0) 0 else n * 4 + k
        val x0 = px(l, 4); val x1 = w - px(r, 4); val y0 = px(t, 4); val y1 = h - px(b, 4)
        if (x1 - x0 < w / 2 || y1 - y0 < h / 2) return m
        val out = Mat(m, org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0)).clone()
        m.release()
        return out
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
        val norm = normalizeLuminance(ch[0], 1.0, ch[1], ch[2])
        val eq = clahe(norm, 1.2)
        norm.release()
        // Levels: paper to white (also wipes faint creases and shadow remnants), text stays dark.
        val leveled = Mat()
        eq.convertTo(leveled, -1, 255.0 / 180.0, -25.0 * 255.0 / 180.0)
        eq.release()
        leveled.copyTo(ch[0]); leveled.release()
        neutralizePaper(ch, 200.0, 10.0)
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
        val (ca, cb) = chromaOf(rgb)
        rgb.release()
        val norm = normalizeLuminance(gray, 0.9, ca, cb)
        gray.release(); ca.release(); cb.release()
        // Levels instead of CLAHE: CLAHE amplified soft stains into grey blotches. Paper goes to clean white,
        // soft shadows (well above ink level) are lifted, dark ink and strokes keep their depth.
        val lut = Mat(1, 256, CvType.CV_8U)
        for (i in 0..255) {
            val t = ((i - 25.0) / (195.0 - 25.0)).coerceIn(0.0, 1.0)
            lut.put(0, i, byteArrayOf((255.0 * t).roundToInt().coerceIn(0, 255).toByte()))
        }
        val eq = Mat(); Core.LUT(norm, lut, eq); lut.release()
        norm.release()
        val out = Mat()
        Imgproc.cvtColor(eq, out, Imgproc.COLOR_GRAY2RGB)
        eq.release()
        return out
    }

    private fun filterBlackWhite(rgb: Mat, s: ProcessSettings): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val (ca, cb) = chromaOf(rgb)
        rgb.release()
        adjustBrightnessContrast(gray, s.brightness, s.contrast)
        Imgproc.GaussianBlur(gray, gray, Size(), 0.8)
        val norm = normalizeLuminance(gray, 1.0, ca, cb)
        gray.release(); ca.release(); cb.release()
        // After normalisation the paper sits near 245. Black = clearly dark (< 150), or a thin stroke that is
        // darker than its surroundings (keeps small, light text) but still not as light as a crease.
        val sigma = max(3.0, min(norm.rows(), norm.cols()) / 230.0)
        val loc = Mat(); Imgproc.GaussianBlur(norm, loc, Size(), sigma)
        loc.convertTo(loc, CvType.CV_32F)
        Core.subtract(loc, Scalar(20.0), loc)
        val n32 = Mat(); norm.convertTo(n32, CvType.CV_32F)
        val darker = Mat(); Core.compare(n32, loc, darker, Core.CMP_LT)
        loc.release(); n32.release()
        val under200 = Mat(); Core.inRange(norm, Scalar(0.0), Scalar(199.0), under200)
        Core.bitwise_and(darker, under200, darker)
        val strong = Mat(); Core.inRange(norm, Scalar(0.0), Scalar(149.0), strong)
        Core.bitwise_or(darker, strong, darker)
        norm.release(); under200.release(); strong.release()
        val bin = Mat(); Core.bitwise_not(darker, bin); darker.release()
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
        val norm = normalizeLuminance(ch[0], 1.0, ch[1], ch[2])
        // Levels + ink curve: paper to white, light ink (e.g. brown print) pulled clearly darker, soft strokes kept.
        val lut = Mat(1, 256, CvType.CV_8U)
        for (i in 0..255) {
            val t = ((i - 45.0) / (205.0 - 45.0)).coerceIn(0.0, 1.0)
            lut.put(0, i, byteArrayOf((255.0 * Math.pow(t, 1.7)).roundToInt().coerceIn(0, 255).toByte()))
        }
        val leveled = Mat()
        Core.LUT(norm, lut, leveled)
        lut.release(); norm.release()
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
        paperWhiteBalance(rgb, 1.25, 0.7)
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
    /** Coarse (about 400 px) model of the page used to separate paper (shadows, folds) from printed pictures. */
    private class PageModel(val f: Double, val ls: Mat, val bg0: Mat, val dist: Mat, val p95: Float) {
        fun release() { ls.release(); bg0.release(); dist.release() }
    }

    private fun percentile(values: FloatArray, q: Double): Float {
        val c = values.copyOf(); c.sort(); return c[(c.size * q).toInt().coerceIn(0, c.size - 1)]
    }

    private fun buildPageModel(l: Mat, a: Mat, b: Mat): PageModel {
        val f = min(1.0, 400.0 / max(l.cols(), l.rows()))
        val ls = Mat(); Imgproc.resize(l, ls, Size(), f, f, Imgproc.INTER_AREA)
        val bg0 = Mat()
        Imgproc.morphologyEx(ls, bg0, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(25.0, 25.0)))
        bg0.convertTo(bg0, CvType.CV_32F)
        Imgproc.GaussianBlur(bg0, bg0, Size(), 3.0)
        fun chroma(src: Mat): Mat {
            val sm = Mat(); Imgproc.resize(src, sm, ls.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            sm.convertTo(sm, CvType.CV_32F)
            Imgproc.GaussianBlur(sm, sm, Size(), 2.0)
            Core.subtract(sm, Scalar(128.0), sm)
            return sm
        }
        val aS = chroma(a); val bS = chroma(b)
        val n = ls.rows() * ls.cols()
        val lsB = ByteArray(n); ls.get(0, 0, lsB)
        val aF = FloatArray(n); aS.get(0, 0, aF)
        val bF = FloatArray(n); bS.get(0, 0, bF)
        val lv = FloatArray(n) { (lsB[it].toInt() and 255).toFloat() }
        val p70 = percentile(lv, 0.70)
        val pa = ArrayList<Float>(); val pb = ArrayList<Float>()
        for (i in 0 until n) if (lv[i] >= p70) { pa.add(aF[i]); pb.add(bF[i]) }
        pa.sort(); pb.sort()
        val ma = pa[pa.size / 2]; val mb = pb[pb.size / 2]
        val d = FloatArray(n) { val x = aF[it] - ma; val y = bF[it] - mb; sqrt(x * x + y * y) }
        val dist = Mat(ls.rows(), ls.cols(), CvType.CV_32F); dist.put(0, 0, d)
        aS.release(); bS.release()
        val bgF = FloatArray(n); bg0.get(0, 0, bgF)
        val p95 = percentile(bgF, 0.95)
        // Soft dark blobs on plain paper (finger/phone shadows, stains) are wider than the 25 px closing:
        // a larger closing lifts them, but only on neutral, still fairly bright paper and by a limited amount.
        val big = Mat(); Imgproc.morphologyEx(ls, big, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(61.0, 61.0)))
        big.convertTo(big, CvType.CV_32F); Imgproc.GaussianBlur(big, big, Size(), 3.0)
        val bigF = FloatArray(n); big.get(0, 0, bigF); big.release()
        var lifted = false
        for (i in 0 until n) if (d[i] < 7f && bgF[i] > 0.5f * p95 && bigF[i] > bgF[i] + 2f) {
            bgF[i] = min(bigF[i], bgF[i] + 70f); lifted = true
        }
        if (lifted) bg0.put(0, 0, bgF)
        return PageModel(f, ls, bg0, dist, p95)
    }

    /**
     * Fold shadows darken a whole strip of the page, printed pictures included. The brightness of the plain
     * paper along every row (and column) is measured; a narrow dip compared with its surroundings is a
     * fold shadow, and that row/column is brightened by the same factor across the full width.
     */
    private fun foldShadowGain(l: Mat, m: PageModel) {
        val w = m.ls.cols(); val h = m.ls.rows(); val n = w * h
        val lsB = ByteArray(n); m.ls.get(0, 0, lsB)
        val dF = FloatArray(n); m.dist.get(0, 0, dF)
        val lv = FloatArray(n) { (lsB[it].toInt() and 255).toFloat() }
        val lim = 0.5f * percentile(lv, 0.95)
        val paper = BooleanArray(n) { dF[it] < 7f && lv[it] > lim }

        fun gainsPart(len: Int, other: Int, j0: Int, j1: Int, at: (Int, Int) -> Int): FloatArray {
            val prof = FloatArray(len) { Float.NaN }
            val tmp = FloatArray(j1 - j0)
            for (i in 0 until len) {
                var c = 0
                for (j in j0 until j1) { val k = at(i, j); if (paper[k]) tmp[c++] = lv[k] }
                if (c >= max(3, (0.06 * (j1 - j0)).toInt())) { val sub = tmp.copyOf(c); sub.sort(); prof[i] = sub[c / 2] }
            }
            var first = -1
            for (i in 0 until len) if (!prof[i].isNaN()) { first = i; break }
            if (first < 0) return FloatArray(len) { 1f }
            var last = first
            for (i in 0 until len) if (!prof[i].isNaN()) {
                if (i - last > 1) for (q in last + 1 until i) prof[q] = prof[last] + (prof[i] - prof[last]) * (q - last) / (i - last).toFloat()
                last = i
            }
            for (i in 0 until first) prof[i] = prof[first]
            for (i in last + 1 until len) prof[i] = prof[last]
            fun smooth(a: FloatArray): FloatArray = FloatArray(len) { i ->
                (a[max(i - 1, 0)] + 2 * a[i] + a[min(i + 1, len - 1)]) / 4f
            }
            val pf = smooth(prof)
            val win = max(5, (len * 0.14).toInt()) or 1
            val half = win / 2
            val base = FloatArray(len) { i ->
                val lo = max(0, i - half); val hi = min(len - 1, i + half)
                val sub = pf.copyOfRange(lo, hi + 1); sub.sort(); sub[sub.size / 2]
            }
            var g = FloatArray(len) { i ->
                val r = (base[i] / max(pf[i], 1f)).coerceIn(1f, 1.7f)
                if (r < 1.04f) 1f else r
            }
            g = smooth(smooth(g))
            return g
        }
        // A real fold crosses the whole page, so its dip must show in EVERY quarter of the row/column;
        // dips caused by text rows or table lines only appear in some of them. Gain = smallest quarter gain.
        fun gains(len: Int, other: Int, at: (Int, Int) -> Int): FloatArray {
            val parts = 4
            val per = (0 until parts).map { q -> gainsPart(len, other, q * other / parts, (q + 1) * other / parts, at) }
            return FloatArray(len) { i -> per.minOf { it[i] } }
        }
        val gy = gains(h, w) { i, j -> i * w + j }
        val gx = gains(w, h) { i, j -> j * w + i }
        if (gy.all { it == 1f } && gx.all { it == 1f }) return

        val gyM = Mat(h, 1, CvType.CV_32F); gyM.put(0, 0, gy)
        val gxM = Mat(1, w, CvType.CV_32F); gxM.put(0, 0, gx)
        val gyBig = Mat(); Imgproc.resize(gyM, gyBig, Size(1.0, l.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val gxBig = Mat(); Imgproc.resize(gxM, gxBig, Size(l.cols().toDouble(), 1.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
        gyM.release(); gxM.release()
        val gyRep = Mat(); Core.repeat(gyBig, 1, l.cols(), gyRep); gyBig.release()
        val gxRep = Mat(); Core.repeat(gxBig, l.rows(), 1, gxRep); gxBig.release()
        val l32 = Mat(); l.convertTo(l32, CvType.CV_32F)
        Core.multiply(l32, gyRep, l32); gyRep.release()
        Core.multiply(l32, gxRep, l32); gxRep.release()
        l32.convertTo(l, CvType.CV_8U); l32.release()
    }

    /** Lifts long, soft, neutral dark bands (paper folds) so they do not survive as dark stripes. */
    private fun repairFolds(l: Mat, m: PageModel) {
        val ls32 = Mat(); m.ls.convertTo(ls32, CvType.CV_32F)
        val dark = Mat(); Core.subtract(m.bg0, ls32, dark); ls32.release()
        Core.max(dark, Scalar(0.0), dark)
        val neutral = Mat(); Imgproc.threshold(m.dist, neutral, 9.0, 1.0, Imgproc.THRESH_BINARY_INV)
        Core.multiply(dark, neutral, dark); neutral.release()
        Imgproc.GaussianBlur(dark, dark, Size(), 1.2)
        val w = dark.cols(); val h = dark.rows()
        val lenH = max(15, (w * 0.28).toInt()); val lenV = max(15, (h * 0.28).toInt())
        fun opened(kw: Int, kh: Int): Mat {
            val o = Mat()
            Imgproc.morphologyEx(dark, o, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kw.toDouble(), kh.toDouble())))
            return o
        }
        // thin soft bands survive the 5 px opening but not the 15 px one (thick dark areas are content, not folds)
        val hThin = opened(lenH, 5); val hThick = opened(lenH, 15)
        val vThin = opened(5, lenV); val vThick = opened(15, lenV)
        Core.subtract(hThin, hThick, hThin); Core.max(hThin, Scalar(0.0), hThin)
        Core.subtract(vThin, vThick, vThin); Core.max(vThin, Scalar(0.0), vThin)
        val fm = Mat(); Core.max(hThin, vThin, fm)
        hThin.release(); hThick.release(); vThin.release(); vThick.release(); dark.release()
        Imgproc.GaussianBlur(fm, fm, Size(), 2.5)
        val full = Mat(); Imgproc.resize(fm, full, l.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); fm.release()
        Core.subtract(full, Scalar(FOLD_THRESHOLD), full)
        Core.max(full, Scalar(0.0), full)
        Core.multiply(full, Scalar(1.8), full)
        val l32 = Mat(); l.convertTo(l32, CvType.CV_32F)
        Core.add(l32, full, l32); full.release()
        l32.convertTo(l, CvType.CV_8U); l32.release()
    }

    /**
     * Estimates how brightly the PAPER is lit at every pixel. Printed pictures (coloured, so different
     * from the paper tint) are not treated as shadow: their value is interpolated from the surrounding paper.
     */
    private fun illumination(l: Mat, m: PageModel): Mat {
        val n = m.ls.rows() * m.ls.cols()
        val bgF = FloatArray(n); m.bg0.get(0, 0, bgF)
        val dF = FloatArray(n); m.dist.get(0, 0, dF)
        val non = Mat(m.ls.rows(), m.ls.cols(), CvType.CV_8U)
        val nonB = ByteArray(n) { if (dF[it] > 9.0f || bgF[it] < 0.25f * m.p95) 1 else 0 }
        non.put(0, 0, nonB)
        Imgproc.dilate(non, non, Mat.ones(5, 5, CvType.CV_8U))
        non.get(0, 0, nonB); non.release()
        val wF = FloatArray(n) { if (nonB[it].toInt() == 0) 1f else 0f }
        val out = bgF.copyOf()
        val filled = BooleanArray(n) { wF[it] > 0.5f }
        val num0 = FloatArray(n) { bgF[it] * wF[it] }
        for (sg in doubleArrayOf(6.0, 14.0, 32.0, 80.0)) {
            val numM = Mat(m.ls.rows(), m.ls.cols(), CvType.CV_32F); numM.put(0, 0, num0)
            val denM = Mat(m.ls.rows(), m.ls.cols(), CvType.CV_32F); denM.put(0, 0, wF)
            Imgproc.GaussianBlur(numM, numM, Size(), sg); Imgproc.GaussianBlur(denM, denM, Size(), sg)
            val nu = FloatArray(n); numM.get(0, 0, nu); val de = FloatArray(n); denM.get(0, 0, de)
            numM.release(); denM.release()
            for (i in 0 until n) if (!filled[i] && de[i] > 0.03f) { out[i] = nu[i] / de[i]; filled[i] = true }
        }
        val med = percentile(bgF, 0.5)
        for (i in 0 until n) if (!filled[i]) out[i] = med
        val small = Mat(m.ls.rows(), m.ls.cols(), CvType.CV_32F); small.put(0, 0, out)
        Imgproc.GaussianBlur(small, small, Size(), 6.0)
        val full = Mat(); Imgproc.resize(small, full, l.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); small.release()
        val floorV = max(60.0, 0.45 * percentile(out, 0.95))
        Core.max(full, Scalar(floorV), full)
        return full
    }

    /**
     * Removes uneven lighting and fold shadows from a luminance channel. [a]/[b] are the Lab chroma
     * channels of the same image (used to tell paper from coloured print).
     */
    private fun normalizeLuminance(l: Mat, strength: Double, a: Mat, b: Mat): Mat {
        val model = buildPageModel(l, a, b)
        foldShadowGain(l, model)
        repairFolds(l, model)
        val bg32 = illumination(l, model)
        model.release()
        val l32 = Mat(); l.convertTo(l32, CvType.CV_32F)
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

    /** Lab a/b channels of an RGB image. */
    private fun chromaOf(rgb: Mat): Pair<Mat, Mat> {
        val lab = Mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>(); Core.split(lab, ch); lab.release()
        ch[0].release()
        return Pair(ch[1], ch[2])
    }

    private fun clahe(l: Mat, clip: Double): Mat {
        val c = Imgproc.createCLAHE(clip, Size(8.0, 8.0))
        val out = Mat()
        c.apply(l, out)
        return out
    }

    /** Gray-world white balance with limited gain, in place. */
    /**
     * Gentle white balance from the brightest part of the page (the paper): removes a warm/cool light cast
     * without turning a coloured print blue the way a grey-world balance does on a one-colour page.
     */
    private fun paperWhiteBalance(rgb: Mat, maxGain: Double, strength: Double) {
        val small = Mat(); Imgproc.resize(rgb, small, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
        val g = Mat(); Imgproc.cvtColor(small, g, Imgproc.COLOR_RGB2GRAY)
        val n = g.rows() * g.cols()
        val gb = ByteArray(n); g.get(0, 0, gb)
        val lv = FloatArray(n) { (gb[it].toInt() and 255).toFloat() }
        val thr = percentile(lv, 0.75).toInt()
        val sum = DoubleArray(3); var cnt = 0
        val px = ByteArray(n * 3); small.get(0, 0, px)
        for (i in 0 until n) if ((gb[i].toInt() and 255) >= thr) {
            for (c in 0 until 3) sum[c] += (px[i * 3 + c].toInt() and 255).toDouble()
            cnt++
        }
        small.release(); g.release()
        if (cnt == 0) return
        val mean = DoubleArray(3) { sum[it] / cnt }
        val target = (mean[0] + mean[1] + mean[2]) / 3.0
        val ch = ArrayList<Mat>()
        Core.split(rgb, ch)
        for (i in 0 until 3) {
            val full = (target / max(mean[i], 1.0)).coerceIn(1.0 / maxGain, maxGain)
            val gain = 1.0 + (full - 1.0) * strength
            ch[i].convertTo(ch[i], -1, gain, 0.0)
        }
        Core.merge(ch, rgb)
        ch.forEach { it.release() }
    }

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
