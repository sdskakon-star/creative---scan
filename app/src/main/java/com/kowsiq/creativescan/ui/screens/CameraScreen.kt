package com.kowsiq.creativescan.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.kowsiq.creativescan.core.ImageProcessor
import com.kowsiq.creativescan.core.LiveResult
import com.kowsiq.creativescan.core.NPoint
import com.kowsiq.creativescan.ui.AppViewModel
import com.kowsiq.creativescan.ui.Screen
import com.kowsiq.creativescan.ui.theme.Cyan
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

private const val READY_FRAMES = 8

@Composable
fun CameraScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var live by remember { mutableStateOf<LiveResult?>(null) }
    var stable by remember { mutableIntStateOf(0) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val capturing = remember { AtomicBoolean(false) }
    val autoCapture = rememberUpdatedState(vm.settings.autoCapture)
    val cardCaptureStage = rememberUpdatedState(vm.idCardCaptureStage)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (!capturing.compareAndSet(false, true)) return
        val file = File(ctx.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing.set(false)
                    vm.onPhotoCaptured(file)
                }

                override fun onError(exc: ImageCaptureException) {
                    capturing.set(false)
                    vm.message = "Capture failed: ${exc.message}"
                }
            }
        )
    }
    val takePhotoState = rememberUpdatedState(::takePhoto)

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    DisposableEffect(hasPermission) {
        var provider: ProcessCameraProvider? = null
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)
            future.addListener({
                val p = future.get()
                provider = p
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                var lastCorners: List<NPoint>? = null
                var steady = 0
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        if (!capturing.get()) {
                            val raw = proxy.toBitmap()
                            val m = Matrix().apply {
                                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                                val k = 480f / max(raw.width, raw.height)
                                postScale(k, k)
                            }
                            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            if (upright !== raw) raw.recycle()
                            val res = ImageProcessor.analyzeFrame(
                                upright,
                                if (cardCaptureStage.value > 0) 0.04 else 0.10,
                                false,
                                480
                            )
                            upright.recycle()

                            val c = res.corners
                            val prev = lastCorners
                            steady = if (c != null && prev != null && movement(c, prev) < 0.02f) steady + 1 else 0
                            lastCorners = c
                            live = res
                            stable = steady

                            val minReadyArea = if (cardCaptureStage.value > 0) 0.10 else 0.25
                            val acceptableLighting = !res.shadowy || cardCaptureStage.value > 0
                            if (c != null && steady >= READY_FRAMES && res.areaFraction > minReadyArea &&
                                acceptableLighting && autoCapture.value
                            ) {
                                mainExecutor.execute { takePhotoState.value() }
                            }
                        }
                    } catch (e: Exception) {
                        // skip this frame
                    } finally {
                        proxy.close()
                    }
                }

                p.unbindAll()
                val cam = p.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                )
                camera = cam
                imageCapture = capture
                cam.cameraControl.setLinearZoom(zoom)
            }, mainExecutor)
        }
        onDispose {
            provider?.unbindAll()
            camera = null
            imageCapture = null
        }
    }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(vm.idCardCaptureStage) {
        // Do not carry the front-side stability counter into the back-side capture.
        stable = 0
        live = null
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }

    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
    val l = live
    val guidance = if (vm.idCardCaptureStage == 1) {
        when {
            l?.corners == null -> "Place the FRONT of the card flat inside the frame."
            stable >= READY_FRAMES -> "Front side ready — hold still."
            else -> "Front side detected — keep the full card visible."
        }
    } else if (vm.idCardCaptureStage == 2) {
        when {
            l?.corners == null -> "Now place the BACK of the card flat inside the frame."
            stable >= READY_FRAMES -> "Back side ready — hold still."
            else -> "Back side detected — keep the full card visible."
        }
    } else when {
        l?.corners == null -> "Find document edges."
        l.areaFraction < 0.25 -> "Move closer."
        l.shadowy -> "Too much shadow."
        stable >= READY_FRAMES -> "Capture ready."
        stable >= 3 -> "Hold steady."
        else -> "Document detected."
    }
    val readyArea = if (vm.idCardCaptureStage > 0) 0.10 else 0.25
    val ready = l?.corners != null && stable >= READY_FRAMES && l.areaFraction > readyArea && !l.shadowy

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            if (vm.idCardCaptureStage == 2) {
                TextButton(onClick = { vm.retakeIdCardFront() }) {
                    Text("Retake front", color = Cyan)
                }
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = vm.settings.autoCapture,
                onClick = { vm.updateSettings(vm.settings.copy(autoCapture = !vm.settings.autoCapture)) },
                label = { Text("Auto") },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = Color.White,
                    selectedContainerColor = Cyan,
                    selectedLabelColor = Color.White
                )
            )
            IconButton(onClick = {
                val c = camera ?: return@IconButton
                val pt = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
                c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                focusPoint = Offset(previewView.width / 2f, previewView.height / 2f)
            }) { Icon(Icons.Rounded.CenterFocusStrong, "Focus", tint = Color.White) }
            if (hasFlash) {
                IconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Flashlight", tint = Color.White)
                }
            }
        }

        if (vm.idCardCaptureStage > 0) {
            Text(
                if (vm.idCardCaptureStage == 1) "ID CARD COPY  •  FRONT SIDE (1 OF 2)" else "ID CARD COPY  •  BACK SIDE (2 OF 2)",
                color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!hasPermission) {
                PermissionPrompt(
                    onRequest = { permLauncher.launch(Manifest.permission.CAMERA) },
                    onSettings = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            } else {
                Box(Modifier.aspectRatio(3f / 4f)) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val c = camera ?: return@detectTapGestures
                                val pt = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                                c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                                focusPoint = offset
                            }
                        }
                    ) {
                        val corners = live?.corners
                        if (corners != null) {
                            val pts = corners.map { Offset(it.x * size.width, it.y * size.height) }
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                lineTo(pts[1].x, pts[1].y)
                                lineTo(pts[2].x, pts[2].y)
                                lineTo(pts[3].x, pts[3].y)
                                close()
                            }
                            val color = if (ready) Color(0xFF3DDC84) else Cyan
                            drawPath(path, color.copy(alpha = 0.18f))
                            drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
                            pts.forEach { drawCircle(color, radius = 6.dp.toPx(), center = it) }
                        }
                        focusPoint?.let {
                            drawCircle(Color.White, radius = 32.dp.toPx(), center = it, style = Stroke(2.dp.toPx()))
                        }
                    }
                    Text(
                        guidance,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                            .clip(RoundedCornerShape(50)).background(Color(0x99000000))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1x", color = Color.White)
                Slider(
                    value = zoom,
                    onValueChange = {
                        zoom = it
                        camera?.cameraControl?.setLinearZoom(it)
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
                )
                Text("Zoom", color = Color.White)
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (vm.pages.isNotEmpty()) {
                        TextButton(onClick = {
                            if (vm.stack.contains(Screen.PAGES)) vm.pop()
                            else { vm.pop(); vm.navigate(Screen.PAGES) }
                        }) { Text("Pages (${vm.pages.size})", color = Color.White) }
                    }
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .padding(7.dp).clip(CircleShape)
                        .background(if (ready) Cyan else Color.White)
                        .clickable(enabled = hasPermission) { takePhoto() }
                )
                Box(Modifier.weight(1f))
            }
        }
    }
}

private fun movement(a: List<NPoint>, b: List<NPoint>): Float {
    var m = 0f
    for (i in 0 until 4) {
        m = max(m, hypot(a[i].x - b[i].x, a[i].y - b[i].y))
    }
    return m
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Camera access is needed to scan documents. Photos never leave your phone.",
            color = Color.White
        )
        Button(onClick = onRequest) { Text("Allow camera") }
        OutlinedButton(onClick = onSettings) { Text("Open app settings", color = Color.White) }
    }
}
