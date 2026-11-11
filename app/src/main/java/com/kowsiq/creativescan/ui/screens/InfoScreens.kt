package com.kowsiq.creativescan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kowsiq.creativescan.core.ScanFilter
import com.kowsiq.creativescan.ui.AppViewModel
import com.kowsiq.creativescan.ui.BrandMark
import com.kowsiq.creativescan.ui.ScreenScaffold
import com.kowsiq.creativescan.ui.theme.Cyan
import com.kowsiq.creativescan.ui.theme.Navy
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    ScreenScaffold("Settings", onBack = { vm.pop() }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("PDF quality", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !s.pdfHigh, onClick = { vm.updateSettings(s.copy(pdfHigh = false)) },
                    label = { Text("Standard (smaller file)") })
                FilterChip(selected = s.pdfHigh, onClick = { vm.updateSettings(s.copy(pdfHigh = true)) },
                    label = { Text("High Quality") })
            }
            Text(
                if (s.pdfHigh) "Up to 300 dpi on A4 when the capture has enough pixels. Low-resolution captures are never upscaled."
                else "About 150 dpi on A4, JPEG quality 75: much smaller files, still readable.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("JPEG quality: ${s.jpegQuality}%", fontWeight = FontWeight.SemiBold)
            Slider(
                value = s.jpegQuality.toFloat(),
                onValueChange = { vm.updateSettings(s.copy(jpegQuality = it.toInt().coerceIn(60, 100))) },
                valueRange = 60f..100f
            )

            SwitchRow("Fit PDF pages to A4", "Off: page size matches the scan itself", s.fitToPage) {
                vm.updateSettings(s.copy(fitToPage = it))
            }
            SwitchRow("Auto capture", "Capture when the document is steady", s.autoCapture) {
                vm.updateSettings(s.copy(autoCapture = it))
            }
            SwitchRow("Also save original capture", "Adds an unprocessed JPEG when exporting JPEGs", s.saveOriginals) {
                vm.updateSettings(s.copy(saveOriginals = it))
            }

            Text("Default filter for new pages", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.values().forEach { f ->
                    FilterChip(selected = s.defaultFilter == f, onClick = { vm.updateSettings(s.copy(defaultFilter = f)) },
                        label = { Text(f.label) })
                }
            }
            Text("Exports are saved in Documents/Creative Scan/", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AboutScreen(vm: AppViewModel) {
    ScreenScaffold("About", onBack = { vm.pop() }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandMark(96.dp)
            Spacer(Modifier.height(16.dp))
            Text("Creative Scan", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text("by Kowsiq", fontSize = 16.sp, color = Cyan)
            Spacer(Modifier.height(4.dp))
            Text("Professional Document Scanner", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            val ctx = LocalContext.current
            val version = remember {
                runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
            }
            Text("Version $version  ·  com.kowsiq.creativescan", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Text(
                "Turns your phone camera into a flat-bed style scanner: automatic edge detection, perspective " +
                    "correction, shadow removal and clean PDF or JPEG export. Everything is processed on your device with OpenCV.",
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun PrivacyScreen(vm: AppViewModel) {
    ScreenScaffold("Privacy", onBack = { vm.pop() }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val points = listOf(
                "100% on-device" to "Detection, enhancement and PDF/JPEG creation all run locally on your phone.",
                "No uploads" to "Your documents are never sent to a server. The app does not even request the INTERNET permission.",
                "No tracking" to "No analytics, no advertising, no hidden data collection.",
                "Minimal permissions" to "Only the camera. On Android 8-9 the storage permission is needed to write to Documents/Creative Scan. Gallery import uses the system photo picker, so it needs no permission.",
                "Your files" to "Exports live in Documents/Creative Scan/. The current unfinished scan is kept in the app's private storage until you finish or start a new scan."
            )
            points.forEach { (title, body) ->
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold, color = Navy)
                    Text(body, lineHeight = 21.sp)
                }
            }
        }
    }
}
