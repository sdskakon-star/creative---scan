package com.kowsiq.creativescan.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kowsiq.creativescan.ui.AppViewModel
import com.kowsiq.creativescan.ui.ScreenScaffold
import com.kowsiq.creativescan.ui.rememberEnsureStorage
import com.kowsiq.creativescan.core.ScanFilter

@Composable
fun PagesScreen(vm: AppViewModel) {
    val ensureStorage = rememberEnsureStorage()
    var filterMenu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(-1) }
    val s = vm.settings

    ScreenScaffold(
        title = "Pages (${vm.pages.size})",
        onBack = { vm.pop() },
        actions = {
            IconButton(onClick = { vm.addPage() }) { Icon(Icons.Rounded.Add, "Add page") }
            Box {
                IconButton(onClick = { filterMenu = true }) { Icon(Icons.Rounded.AutoFixHigh, "Apply filter to all pages") }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    Text("Apply to all pages", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScanFilter.values().forEach { f ->
                        DropdownMenuItem(text = { Text(f.label) }, onClick = {
                            vm.applyFilterToAll(f); filterMenu = false
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Discard scan") }, onClick = {
                        filterMenu = false; confirmDiscard = true
                    })
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.docName,
                        onValueChange = { vm.docName = it },
                        label = { Text("File name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("PDF:", fontSize = 13.sp)
                        FilterChip(selected = !s.pdfHigh, onClick = { vm.updateSettings(s.copy(pdfHigh = false)) },
                            label = { Text("Standard") })
                        FilterChip(selected = s.pdfHigh, onClick = { vm.updateSettings(s.copy(pdfHigh = true)) },
                            label = { Text("High Quality") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { ensureStorage { vm.exportPdf() } },
                            enabled = vm.pages.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Save PDF")
                        }
                        OutlinedButton(
                            onClick = { ensureStorage { vm.exportJpegs() } },
                            enabled = vm.pages.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Image, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Save JPEG")
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (vm.pages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No pages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vm.addPage() }) { Text("Add page") }
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(vm.pages, key = { _, p -> p.id }) { i, page ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)
                    ) {
                        Box(
                            Modifier.size(width = 84.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            val t = vm.thumbs[page.id]
                            if (t != null) Image(t, "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else CircularProgressIndicator(Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Page ${i + 1} of ${vm.pages.size}", fontWeight = FontWeight.SemiBold)
                            Text(page.settings.filter.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                IconButton(onClick = { vm.movePage(i, -1) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "Move up") }
                                IconButton(onClick = { vm.movePage(i, 1) }, enabled = i < vm.pages.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move down") }
                                IconButton(onClick = { vm.rotatePage(i, false) }) { Icon(Icons.Rounded.RotateLeft, "Rotate left") }
                                IconButton(onClick = { vm.rotatePage(i, true) }) { Icon(Icons.Rounded.RotateRight, "Rotate right") }
                            }
                            Row {
                                IconButton(onClick = { vm.editPage(i) }) { Icon(Icons.Rounded.Edit, "Edit") }
                                IconButton(onClick = { vm.retakePage(i) }) { Icon(Icons.Rounded.Replay, "Retake") }
                                IconButton(onClick = { confirmDelete = i }) { Icon(Icons.Rounded.Delete, "Delete") }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { vm.addPage() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("Add page")
                    }
                }
            }
        }
    }

    if (confirmDelete >= 0) {
        AlertDialog(
            onDismissRequest = { confirmDelete = -1 },
            title = { Text("Delete page ${confirmDelete + 1}?") },
            text = { Text("This page will be removed from the current scan.") },
            confirmButton = { TextButton(onClick = { vm.deletePage(confirmDelete); confirmDelete = -1 }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = -1 }) { Text("Cancel") } }
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this scan?") },
            text = { Text("All ${vm.pages.size} unsaved page(s) will be removed. Files already exported are not affected.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.clearSession(); vm.pop() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } }
        )
    }
}
