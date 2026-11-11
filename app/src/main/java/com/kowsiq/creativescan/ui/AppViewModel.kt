package com.kowsiq.creativescan.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kowsiq.creativescan.core.ImageIO
import com.kowsiq.creativescan.core.IdCardLayout
import com.kowsiq.creativescan.core.ImageProcessor
import com.kowsiq.creativescan.core.NPoint
import com.kowsiq.creativescan.core.PdfImage
import com.kowsiq.creativescan.core.PdfWriter
import com.kowsiq.creativescan.core.ProcessSettings
import com.kowsiq.creativescan.core.ScanFilter
import com.kowsiq.creativescan.core.ScanPage
import com.kowsiq.creativescan.data.AppSettings
import com.kowsiq.creativescan.data.DocItem
import com.kowsiq.creativescan.data.DocumentRepository
import com.kowsiq.creativescan.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Screen { HOME, CAMERA, EDIT, PAGES, DOCS, SETTINGS, ABOUT, PRIVACY }

enum class DocSort(val label: String) {
    DATE_DESC("Newest first"), DATE_ASC("Oldest first"), NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A")
}

data class ExportResult(val uris: List<Uri>, val mime: String, val summary: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val sessionDir get() = File(ctx.filesDir, "session").apply { mkdirs() }

    // ---------- state ----------
    var settings by mutableStateOf(SettingsStore.load(app))
        private set
    val stack = mutableStateListOf(Screen.HOME)
    val pages = mutableStateListOf<ScanPage>()
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    var editIndex by mutableStateOf(-1)
    var retakeIndex by mutableStateOf(-1)
    /** 0 = regular scan, 1 = capture card front, 2 = capture card back. */
    var idCardCaptureStage by mutableStateOf(0)
        private set
    private var idCardFrontPage: ScanPage? = null
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var exportResult by mutableStateOf<ExportResult?>(null)
    var docName by mutableStateOf(defaultName())

    val documents = mutableStateListOf<DocItem>()
    var docQuery by mutableStateOf("")
    var docSort by mutableStateOf(DocSort.DATE_DESC)
    var docsRecentOnly by mutableStateOf(false)

    private val docThumbCache = LruCache<String, ImageBitmap>(80)

    init {
        restoreSession()
    }

    // ---------- navigation ----------
    fun navigate(s: Screen) { stack.add(s) }
    fun pop() {
        if (stack.size > 1) {
            if (stack.last() == Screen.CAMERA && idCardCaptureStage > 0) cancelIdCardScan()
            stack.removeAt(stack.lastIndex)
        }
    }

    fun startScan() {
        cancelIdCardScan()
        clearSession()
        retakeIndex = -1
        navigate(Screen.CAMERA)
    }

    /** Starts a guided two-sided ID-1 card copy, then places both sides on one A4 page. */
    fun startIdCardScan() {
        cancelIdCardScan()
        clearSession()
        retakeIndex = -1
        idCardCaptureStage = 1
        navigate(Screen.CAMERA)
    }

    private fun cancelIdCardScan() {
        idCardFrontPage?.file?.delete()
        idCardFrontPage = null
        idCardCaptureStage = 0
    }

    fun retakeIdCardFront() {
        if (idCardCaptureStage != 2) return
        idCardFrontPage?.file?.delete()
        idCardFrontPage = null
        idCardCaptureStage = 1
        message = "Please capture the front side again."
    }

    fun addPage() { retakeIndex = -1; navigate(Screen.CAMERA) }
    fun retakePage(i: Int) { retakeIndex = i; navigate(Screen.CAMERA) }
    fun editPage(i: Int) { editIndex = i; navigate(Screen.EDIT) }

    fun openDocuments(recent: Boolean) {
        docsRecentOnly = recent
        docSort = DocSort.DATE_DESC
        docQuery = ""
        navigate(Screen.DOCS)
    }

    // ---------- settings ----------
    fun updateSettings(s: AppSettings) {
        settings = s
        SettingsStore.save(ctx, s)
    }

    // ---------- capture / import ----------
    fun onPhotoCaptured(file: File) {
        viewModelScope.launch {
            busy = if (idCardCaptureStage > 0) "Processing card side…" else "Processing capture…"
            val isCardSide = idCardCaptureStage > 0
            val page = withContext(Dispatchers.Default) {
                createPage(
                    Uri.fromFile(file),
                    minAreaFraction = if (isCardSide) 0.06 else 0.15,
                    requireDetection = isCardSide
                )
            }
            file.delete()
            if (page == null) {
                busy = null
                message = if (isCardSide) {
                    "Could not detect all four card edges. Place the card flat on a darker, plain surface and try again."
                } else {
                    "Could not read the captured image. Please try again."
                }
                return@launch
            }

            if (idCardCaptureStage == 1) {
                idCardFrontPage = page
                idCardCaptureStage = 2
                busy = null
                if (stack.last() == Screen.CAMERA) stack.removeAt(stack.lastIndex)
                navigate(Screen.CAMERA)
                message = "Front side captured. Now scan the back side."
                return@launch
            }

            if (idCardCaptureStage == 2) {
                val front = idCardFrontPage
                if (front == null) {
                    page.file.delete()
                    idCardCaptureStage = 1
                    busy = null
                    message = "Front side is missing. Please scan the front again."
                    return@launch
                }
                val a4Page = withContext(Dispatchers.Default) { composeIdCardA4Page(front, page) }
                front.file.delete()
                page.file.delete()
                idCardFrontPage = null
                idCardCaptureStage = 0
                busy = null
                if (a4Page == null) {
                    message = "Could not create the A4 card copy. Please scan both sides again."
                    if (stack.last() == Screen.CAMERA) stack.removeAt(stack.lastIndex)
                    navigate(Screen.HOME)
                    return@launch
                }
                placeCapturedPage(a4Page)
                if (stack.last() == Screen.CAMERA) stack.removeAt(stack.lastIndex)
                navigate(Screen.EDIT)
                message = "Both sides placed on one A4 page. Review before saving."
                return@launch
            }

            busy = null
            placeCapturedPage(page)
            if (stack.last() == Screen.CAMERA) stack.removeAt(stack.lastIndex)
            if (stack.last() != Screen.EDIT) navigate(Screen.EDIT)
        }
    }

    /**
     * Makes a true A4 portrait raster at 200 dpi. The two ID-1 sides are perspective-corrected
     * separately, scaled to physical card size, centred horizontally and grouped vertically.
     * The pair as a whole is centred on the sheet, with a white background and no watermark.
     */
    private fun composeIdCardA4Page(front: ScanPage, back: ScanPage): ScanPage? {
        val frontSource = ImageIO.decodeFile(front.file, 4096) ?: return null
        val backSource = ImageIO.decodeFile(back.file, 4096)
        if (backSource == null) {
            frontSource.recycle()
            return null
        }
        var frontCrop: Bitmap? = null
        var backCrop: Bitmap? = null
        var sheet: Bitmap? = null
        try {
            val cardSettings = ProcessSettings(
                filter = ScanFilter.ENHANCED,
                brightness = 0f,
                contrast = 1f,
                sharpness = 0.12f,
                autoStraighten = false
            )
            val frontImage0 = ImageProcessor.render(frontSource, front.corners, cardSettings, 1500, IdCardLayout.CARD_ASPECT)
            frontCrop = frontImage0
            val backImage0 = ImageProcessor.render(backSource, back.corners, cardSettings, 1500, IdCardLayout.CARD_ASPECT)
            backCrop = backImage0

            // Upright (portrait) cards are turned to landscape so both sides share one layout.
            val frontImage = landscape(frontImage0).also { frontCrop = it }
            val backImage = landscape(backImage0).also { backCrop = it }

            val layout = IdCardLayout.compute()
            val a4Bitmap = Bitmap.createBitmap(
                IdCardLayout.PAGE_WIDTH.toInt(), IdCardLayout.PAGE_HEIGHT.toInt(), Bitmap.Config.ARGB_8888
            )
            sheet = a4Bitmap
            a4Bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(a4Bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            canvas.drawBitmap(frontImage, null, layout.front.toRectF(), paint)
            canvas.drawBitmap(backImage, null, layout.back.toRectF(), paint)

            val id = UUID.randomUUID().toString()
            val output = File(sessionDir, "$id.jpg")
            ImageIO.saveJpeg(a4Bitmap, output, 98)
            return ScanPage(
                id = id,
                file = output,
                corners = ImageProcessor.defaultCorners(0f),
                settings = ProcessSettings(filter = ScanFilter.ORIGINAL, sharpness = 0f, autoStraighten = false),
                isIdCardCopy = true
            )
        } catch (e: Exception) {
            return null
        } finally {
            frontSource.recycle()
            backSource.recycle()
            frontCrop?.recycle()
            backCrop?.recycle()
            sheet?.recycle()
        }
    }

    private fun IdCardLayout.Box.toRectF() = RectF(left, top, right, bottom)

    /** Returns [src] if landscape, otherwise a clockwise-rotated copy (the source is recycled). */
    private fun landscape(src: Bitmap): Bitmap {
        if (src.width >= src.height) return src
        val m = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    fun importUris(uris: List<Uri>) {
        viewModelScope.launch {
            busy = "Importing…"
            retakeIndex = -1
            clearSession()
            var first = -1
            for (u in uris) {
                val page = withContext(Dispatchers.Default) { createPage(u) } ?: continue
                pages.add(page)
                refreshThumb(page)
                if (first < 0) first = pages.lastIndex
            }
            persist()
            busy = null
            if (first < 0) { message = "Could not import the selected images"; return@launch }
            if (pages.size == 1) { editIndex = 0; navigate(Screen.EDIT) } else navigate(Screen.PAGES)
        }
    }

    private fun placeCapturedPage(page: ScanPage) {
        if (retakeIndex in pages.indices) {
            pages[retakeIndex].file.delete()
            pages[retakeIndex] = page
            editIndex = retakeIndex
        } else {
            pages.add(page)
            editIndex = pages.lastIndex
        }
        retakeIndex = -1
        refreshThumb(page)
        persist()
    }

    private fun createPage(
        uri: Uri,
        minAreaFraction: Double = 0.15,
        requireDetection: Boolean = false
    ): ScanPage? {
        val bmp = ImageIO.decodeUri(ctx, uri, 4096) ?: return null
        val det = try {
            ImageProcessor.analyzeFrame(bmp, minAreaFraction, true, 1000)
        } catch (e: Exception) {
            bmp.recycle()
            return null
        }
        if (requireDetection && (det.corners == null || det.areaFraction < minAreaFraction)) {
            bmp.recycle()
            return null
        }
        val id = UUID.randomUUID().toString()
        val f = File(sessionDir, "$id.jpg")
        ImageIO.saveJpeg(bmp, f, 97)
        bmp.recycle()
        return ScanPage(
            id = id,
            file = f,
            corners = det.corners ?: ImageProcessor.defaultCorners(),
            settings = ProcessSettings(filter = settings.defaultFilter)
        )
    }

    // ---------- pages ----------
    suspend fun loadPreviewSource(page: ScanPage): Bitmap? =
        withContext(Dispatchers.IO) { ImageIO.decodeFile(page.file, 1400) }

    fun updatePage(index: Int, corners: List<NPoint>, s: ProcessSettings) {
        if (index !in pages.indices) return
        val p = pages[index].copy(corners = corners, settings = s)
        pages[index] = p
        refreshThumb(p)
        persist()
    }

    fun deletePage(index: Int) {
        if (index !in pages.indices) return
        val p = pages.removeAt(index)
        p.file.delete()
        thumbs.remove(p.id)
        persist()
    }

    fun movePage(index: Int, delta: Int) {
        val target = index + delta
        if (index !in pages.indices || target !in pages.indices) return
        val p = pages.removeAt(index)
        pages.add(target, p)
        persist()
    }

    fun rotatePage(index: Int, clockwise: Boolean) {
        if (index !in pages.indices) return
        val old = pages[index]
        val rot = ((old.settings.rotation + if (clockwise) 90 else 270) % 360)
        val p = old.copy(settings = old.settings.copy(rotation = rot))
        pages[index] = p
        refreshThumb(p)
        persist()
    }

    fun applyFilterToAll(filter: ScanFilter) {
        for (i in pages.indices) {
            val p = pages[i].copy(settings = pages[i].settings.copy(filter = filter))
            pages[i] = p
            refreshThumb(p)
        }
        persist()
    }

    private fun refreshThumb(page: ScanPage) {
        viewModelScope.launch(Dispatchers.Default) {
            val src = ImageIO.decodeFile(page.file, 640) ?: return@launch
            try {
                val out = ImageProcessor.render(src, page.corners, page.settings, 480)
                withContext(Dispatchers.Main) { thumbs[page.id] = out.asImageBitmap() }
            } catch (e: Exception) {
                // thumbnail is optional
            } finally {
                src.recycle()
            }
        }
    }

    fun clearSession() {
        pages.forEach { it.file.delete() }
        pages.clear()
        thumbs.clear()
        docName = defaultName()
        persist()
    }

    // ---------- session persistence (interrupted scans survive process death) ----------
    private fun persist() {
        try {
            val arr = JSONArray()
            pages.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("corners", JSONArray(p.corners.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }))
                    put("filter", p.settings.filter.name)
                    put("brightness", p.settings.brightness.toDouble())
                    put("contrast", p.settings.contrast.toDouble())
                    put("sharpness", p.settings.sharpness.toDouble())
                    put("auto", p.settings.autoStraighten)
                    put("rotation", p.settings.rotation)
                    put("isIdCardCopy", p.isIdCardCopy)
                })
            }
            File(sessionDir, "session.json").writeText(arr.toString())
        } catch (e: Exception) {
            // best effort
        }
    }

    private fun restoreSession() {
        try {
            val f = File(sessionDir, "session.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = File(sessionDir, o.getString("id") + ".jpg")
                if (!file.exists()) continue
                val c = o.getJSONArray("corners")
                val corners = (0 until 4).map { NPoint(c.getDouble(it * 2).toFloat(), c.getDouble(it * 2 + 1).toFloat()) }
                val s = ProcessSettings(
                    filter = runCatching { ScanFilter.valueOf(o.getString("filter")) }.getOrDefault(ScanFilter.ENHANCED),
                    brightness = o.getDouble("brightness").toFloat(),
                    contrast = o.getDouble("contrast").toFloat(),
                    sharpness = o.getDouble("sharpness").toFloat(),
                    autoStraighten = o.getBoolean("auto"),
                    rotation = o.getInt("rotation")
                )
                val page = ScanPage(o.getString("id"), file, corners, s, o.optBoolean("isIdCardCopy", false))
                pages.add(page)
                refreshThumb(page)
            }
        } catch (e: Exception) {
            pages.clear()
        }
    }

    // ---------- export ----------
    fun exportPdf() {
        val snapshot = pages.toList()
        if (snapshot.isEmpty()) return
        val s = settings
        val base = DocumentRepository.sanitize(docName)
        viewModelScope.launch {
            busy = "Creating PDF…"
            try {
                val uri = withContext(Dispatchers.IO) {
                    val maxSide = if (s.pdfHigh) 3508 else 2100
                    val quality = if (s.pdfHigh) 92 else 75
                    val dpi = if (s.pdfHigh) 300 else 150
                    DocumentRepository.save(ctx, "$base.pdf", "application/pdf") { out ->
                        PdfWriter(out.buffered()).write(
                            snapshot.map { p -> { renderJpeg(p, maxSide, quality) } },
                            s.fitToPage, dpi
                        )
                    }
                }
                exportResult = ExportResult(listOf(uri), "application/pdf", "PDF saved to Documents/Creative Scan/$base.pdf")
                loadDocuments()
            } catch (e: Exception) {
                message = "PDF export failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun exportJpegs() {
        val snapshot = pages.toList()
        if (snapshot.isEmpty()) return
        val s = settings
        val base = DocumentRepository.sanitize(docName)
        viewModelScope.launch {
            val uris = ArrayList<Uri>()
            try {
                snapshot.forEachIndexed { i, p ->
                    busy = "Saving JPEG ${i + 1} of ${snapshot.size}…"
                    withContext(Dispatchers.IO) {
                        val suffix = if (snapshot.size == 1) "" else "_page${i + 1}"
                        val src = ImageIO.decodeFile(p.file, 4096) ?: error("Missing page image")
                        val bmp = ImageProcessor.render(src, p.corners, p.settings, 0)
                        src.recycle()
                        uris.add(DocumentRepository.save(ctx, "$base$suffix.jpg", "image/jpeg") { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, s.jpegQuality, out)
                        })
                        bmp.recycle()
                        if (s.saveOriginals) {
                            uris.add(DocumentRepository.save(ctx, "$base${suffix}_original.jpg", "image/jpeg") { out ->
                                p.file.inputStream().use { it.copyTo(out) }
                            })
                        }
                    }
                }
                exportResult = ExportResult(uris, "image/jpeg", "${uris.size} JPEG file(s) saved to Documents/Creative Scan/")
                loadDocuments()
            } catch (e: Exception) {
                message = "JPEG export failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    private fun renderJpeg(page: ScanPage, maxSide: Int, quality: Int): PdfImage {
        val src = ImageIO.decodeFile(page.file, 4096) ?: error("Missing page image")
        val out = ImageProcessor.render(src, page.corners, page.settings, maxSide)
        src.recycle()
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val img = PdfImage(baos.toByteArray(), out.width, out.height, page.isIdCardCopy)
        out.recycle()
        return img
    }

    // ---------- documents ----------
    fun loadDocuments() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { DocumentRepository.list(ctx) }
            documents.clear()
            documents.addAll(list)
        }
    }

    fun renameDocument(item: DocItem, newName: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { DocumentRepository.rename(ctx, item, newName) }
            message = if (ok) "Renamed" else "Could not rename this file"
            loadDocuments()
        }
    }

    fun deleteDocument(item: DocItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { DocumentRepository.delete(ctx, item) }
            message = if (ok) "Deleted" else "Could not delete this file"
            loadDocuments()
        }
    }

    suspend fun docThumb(item: DocItem): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${item.uri}#${item.modified}"
        docThumbCache.get(key)?.let { return@withContext it }
        val bmp = try {
            if (item.isPdf) pdfThumb(item.uri) else imageThumb(item.uri)
        } catch (e: Exception) {
            null
        }
        bmp?.asImageBitmap()?.also { docThumbCache.put(key, it) }
    }

    private fun pdfThumb(uri: Uri): Bitmap? {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        try {
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(0)
                try {
                    val w = 320
                    val h = (w * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
    }

    private fun imageThumb(uri: Uri): Bitmap? = ImageIO.decodeUri(ctx, uri, 320)

    companion object {
        fun defaultName(): String =
            "Scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    }
}
