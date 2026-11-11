package com.kowsiq.creativescan.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kowsiq.creativescan.ui.theme.Cyan
import com.kowsiq.creativescan.ui.theme.Navy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The app logo: a document with a scanner corner-frame. Drawn in code so it scales cleanly. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(Navy, cornerRadius = CornerRadius(s * 0.22f, s * 0.22f))
        // document
        drawRoundRect(
            Color.White,
            topLeft = Offset(s * 0.34f, s * 0.28f),
            size = androidx.compose.ui.geometry.Size(s * 0.32f, s * 0.44f),
            cornerRadius = CornerRadius(s * 0.03f, s * 0.03f)
        )
        val lineColor = Color(0xFF9FB3D1)
        val lw = s * 0.022f
        for ((i, f) in listOf(1f, 1f, 0.6f).withIndex()) {
            val y = s * (0.46f + i * 0.065f)
            drawLine(lineColor, Offset(s * 0.38f, y), Offset(s * (0.38f + 0.24f * f), y), lw, StrokeCap.Round)
        }
        // corner frame
        val x0 = s * 0.22f; val y0 = s * 0.20f; val x1 = s * 0.78f; val y1 = s * 0.80f
        val l = s * 0.12f
        val w = s * 0.035f
        fun seg(ax: Float, ay: Float, bx: Float, by: Float) =
            drawLine(Cyan, Offset(ax, ay), Offset(bx, by), w, StrokeCap.Round)
        seg(x0, y0, x0 + l, y0); seg(x0, y0, x0, y0 + l)
        seg(x1, y0, x1 - l, y0); seg(x1, y0, x1, y0 + l)
        seg(x0, y1, x0 + l, y1); seg(x0, y1, x0, y1 - l)
        seg(x1, y1, x1 - l, y1); seg(x1, y1, x1, y1 - l)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = bottomBar,
        content = content
    )
}

// ---------- sharing / opening ----------

fun shareUris(ctx: Context, uris: List<Uri>, mime: String) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mime
    val clip = ClipData.newRawUri("scan", uris[0])
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app available to share", Toast.LENGTH_SHORT).show()
    }
}

fun openUri(ctx: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

// ---------- formatting ----------

fun formatDate(epochSeconds: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
    else -> "$bytes B"
}

// ---------- storage permission (Android 8/9 only) ----------

private fun legacyStorageGranted(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Returns a function that runs an action once storage access is available. On Android 10+
 * the action runs immediately because MediaStore needs no permission.
 */
@Composable
fun rememberEnsureStorage(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) pending?.invoke()
        pending = null
    }
    return { action ->
        if (legacyStorageGranted(ctx)) {
            action()
        } else {
            pending = action
            launcher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }
}
