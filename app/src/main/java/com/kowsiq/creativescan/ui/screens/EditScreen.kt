package com.kowsiq.creativescan.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kowsiq.creativescan.core.ImageProcessor
import com.kowsiq.creativescan.core.NPoint
import com.kowsiq.creativescan.core.ProcessSettings
import com.kowsiq.creativescan.core.ScanFilter
import com.kowsiq.creativescan.core.ScanPage
import com.kowsiq.creativescan.ui.AppViewModel
import com.kowsiq.creativescan.ui.Screen
import com.kowsiq.creativescan.ui.ScreenScaffold
import com.kowsiq.creativescan.ui.theme.Cyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max

@Composable
fun EditScreen(vm: AppViewModel) {
    val index = vm.editIndex
    val page = vm.pages.getOrNull(index)
    if (page == null) {
        LaunchedEffect(Unit) { vm.pop() }
        return
    }
    EditContent(vm, index, page)
}

@Composable
private fun EditContent(vm: AppViewModel, index: Int, page: ScanPage) {
    val scope = rememberCoroutineScope()
    var corners by remember(page.id) { mutableStateOf(page.corners) }
    var st by remember(page.id) { mutableStateOf(page.settings) }
    var source by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    var tab by remember(page.id) { mutableIntStateOf(0) }
    var showBefore by remember(page.id) { mutableStateOf(false) }
    var result by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    var working by remember(page.id) { mutableStateOf(false) }

    LaunchedEffect(page.id) { source = vm.loadPreviewSource(page) }

    // Re-render the enhanced preview (debounced) whenever something relevant changes.
    LaunchedEffect(source, corners, st, showBefore, tab) {
        val src = source ?: return@LaunchedEffect
        if (tab != 1) return@LaunchedEffect
        delay(120)
        working = true
        val bmp = withContext(Dispatchers.Default) {
            val s = if (showBefore) ProcessSettings(ScanFilter.ORIGINAL, 0f, 1f, 0f, false, st.rotation) else st
            ImageProcessor.render(src, corners, s, 1400)
        }
        result = bmp.asImageBitmap()
        working = false
    }

    ScreenScaffold(
        title = "Edit page ${index + 1}",
        onBack = { vm.pop() },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = { vm.retakePage(index) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Retake")
                    }
                    Button(
                        onClick = {
                            vm.updatePage(index, corners, st)
                            vm.pop()
                            if (vm.stack.last() != Screen.PAGES) vm.navigate(Screen.PAGES)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save")
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Crop") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Enhance") })
            }

            Box(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                val src = source
                if (tab == 0) {
                    if (src == null) CircularProgressIndicator()
                    else CornerEditor(src, corners, { corners = it })
                } else {
                    val r = result
                    if (r != null) {
                        Image(r, contentDescription = "Preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    if (r == null || working) CircularProgressIndicator()
                }
            }

            if (tab == 0) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Drag the four corners onto the document edges. A magnified view appears while you drag.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val s = source ?: return@OutlinedButton
                            scope.launch {
                                working = true
                                val d = withContext(Dispatchers.Default) { ImageProcessor.analyzeFrame(s, 0.12, true, 900) }
                                corners = d.corners ?: ImageProcessor.defaultCorners()
                                working = false
                            }
                        }) { Text("Auto-detect") }
                        OutlinedButton(onClick = { corners = ImageProcessor.defaultCorners(0f) }) { Text("Full image") }
                    }
                }
            } else {
                EnhanceControls(
                    st = st,
                    showBefore = showBefore,
                    onSettings = { st = it },
                    onBefore = { showBefore = it },
                    onRevert = {
                        st = ProcessSettings(ScanFilter.ORIGINAL, 0f, 1f, 0f, false, 0)
                        showBefore = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EnhanceControls(
    st: ProcessSettings,
    showBefore: Boolean,
    onSettings: (ProcessSettings) -> Unit,
    onBefore: (Boolean) -> Unit,
    onRevert: () -> Unit
) {
    Column(
        Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ScanFilter.values().toList()) { f ->
                FilterChip(
                    selected = st.filter == f,
                    onClick = { onSettings(st.copy(filter = f)) },
                    label = { Text(f.label) }
                )
            }
        }
        LabeledSlider("Brightness", st.brightness, -100f..100f, "${st.brightness.toInt()}") {
            onSettings(st.copy(brightness = it))
        }
        LabeledSlider("Contrast", st.contrast, 0.5f..1.5f, String.format(java.util.Locale.US, "%.2f", st.contrast)) {
            onSettings(st.copy(contrast = it))
        }
        LabeledSlider("Sharpness", st.sharpness, 0f..1f, "${(st.sharpness * 100).toInt()}%") {
            onSettings(st.copy(sharpness = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSettings(st.copy(rotation = (st.rotation + 270) % 360)) }) {
                Icon(Icons.Rounded.RotateLeft, "Rotate left")
            }
            IconButton(onClick = { onSettings(st.copy(rotation = (st.rotation + 90) % 360)) }) {
                Icon(Icons.Rounded.RotateRight, "Rotate right")
            }
            FilterChip(
                selected = showBefore,
                onClick = { onBefore(!showBefore) },
                label = { Text("Before / After") },
                leadingIcon = { Icon(Icons.Rounded.Compare, null, Modifier.size(18.dp)) }
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRevert) { Text("Revert to original") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-straighten", Modifier.weight(1f))
            Switch(checked = st.autoStraighten, onCheckedChange = { onSettings(st.copy(autoStraighten = it)) })
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/**
 * Shows the original image with four draggable corner handles. While a handle is dragged a
 * magnified view of the area around it is shown in the top corner, so edges can be placed
 * precisely.
 */
@Composable
private fun CornerEditor(bitmap: Bitmap, corners: List<NPoint>, onChange: (List<NPoint>) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val latestCorners by rememberUpdatedState(corners)
    val latestOnChange by rememberUpdatedState(onChange)
    var active by remember { mutableIntStateOf(-1) }

    Box(Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())) {
        Image(image, contentDescription = "Original", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        var best = -1
                        var bestDist = Float.MAX_VALUE
                        latestCorners.forEachIndexed { i, p ->
                            val d = hypot(p.x * w - o.x, p.y * h - o.y)
                            if (d < bestDist) { bestDist = d; best = i }
                        }
                        active = if (bestDist < 72.dp.toPx()) best else -1
                    },
                    onDrag = { change, drag ->
                        val a = active
                        if (a >= 0) {
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val p = latestCorners[a]
                            val np = NPoint(
                                (p.x + drag.x / w).coerceIn(0f, 1f),
                                (p.y + drag.y / h).coerceIn(0f, 1f)
                            )
                            latestOnChange(latestCorners.toMutableList().also { it[a] = np })
                        }
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 }
                )
            }
        ) {
            val w = size.width
            val h = size.height
            val pts = corners.map { Offset(it.x * w, it.y * h) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }
            drawPath(path, Cyan.copy(alpha = 0.16f))
            drawPath(path, Cyan, style = Stroke(width = 2.5.dp.toPx()))
            pts.forEachIndexed { i, p ->
                val r = if (i == active) 16.dp.toPx() else 12.dp.toPx()
                drawCircle(Color.White, radius = r, center = p)
                drawCircle(Cyan, radius = r, center = p, style = Stroke(3.dp.toPx()))
            }

            if (active >= 0) {
                val p = corners[active]
                val box = 112.dp.toPx()
                val margin = 8.dp.toPx()
                val lx = if (p.x < 0.5f) w - box - margin else margin
                val ly = margin
                val half = max(8, (image.width * 0.05f).toInt())
                val cx = (p.x * image.width).toInt()
                val cy = (p.y * image.height).toInt()
                val sx = (cx - half).coerceIn(0, max(0, image.width - 2 * half))
                val sy = (cy - half).coerceIn(0, max(0, image.height - 2 * half))
                clipRect(lx, ly, lx + box, ly + box) {
                    drawImage(
                        image,
                        srcOffset = IntOffset(sx, sy),
                        srcSize = IntSize(2 * half, 2 * half),
                        dstOffset = IntOffset(lx.toInt(), ly.toInt()),
                        dstSize = IntSize(box.toInt(), box.toInt())
                    )
                }
                drawRect(Color.White, topLeft = Offset(lx, ly), size = androidx.compose.ui.geometry.Size(box, box), style = Stroke(3.dp.toPx()))
                val mx = lx + (cx - sx).toFloat() / (2 * half) * box
                val my = ly + (cy - sy).toFloat() / (2 * half) * box
                drawLine(Cyan, Offset(mx - 12.dp.toPx(), my), Offset(mx + 12.dp.toPx(), my), 1.5.dp.toPx())
                drawLine(Cyan, Offset(mx, my - 12.dp.toPx()), Offset(mx, my + 12.dp.toPx()), 1.5.dp.toPx())
            }
        }
    }
}
