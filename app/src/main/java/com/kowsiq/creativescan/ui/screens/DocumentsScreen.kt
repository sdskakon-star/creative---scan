package com.kowsiq.creativescan.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kowsiq.creativescan.data.DocItem
import com.kowsiq.creativescan.ui.AppViewModel
import com.kowsiq.creativescan.ui.DocSort
import com.kowsiq.creativescan.ui.ScreenScaffold
import com.kowsiq.creativescan.ui.formatDate
import com.kowsiq.creativescan.ui.formatSize
import com.kowsiq.creativescan.ui.openUri
import com.kowsiq.creativescan.ui.rememberEnsureStorage
import com.kowsiq.creativescan.ui.shareUris

@Composable
fun DocumentsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ensureStorage = rememberEnsureStorage()
    LaunchedEffect(Unit) { ensureStorage { vm.loadDocuments() } }

    var searching by remember { mutableStateOf(vm.docQuery.isNotEmpty()) }
    var sortMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DocItem?>(null) }
    var deleteTarget by remember { mutableStateOf<DocItem?>(null) }

    val visible = remember(vm.documents.toList(), vm.docQuery, vm.docSort, vm.docsRecentOnly) {
        var list = vm.documents.filter { it.name.contains(vm.docQuery.trim(), ignoreCase = true) }
        list = when (vm.docSort) {
            DocSort.DATE_DESC -> list.sortedByDescending { it.modified }
            DocSort.DATE_ASC -> list.sortedBy { it.modified }
            DocSort.NAME_ASC -> list.sortedBy { it.name.lowercase() }
            DocSort.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
        }
        if (vm.docsRecentOnly) list.take(20) else list
    }

    ScreenScaffold(
        title = if (vm.docsRecentOnly) "Recent Scans" else "My Documents",
        onBack = { vm.pop() },
        actions = {
            IconButton(onClick = {
                searching = !searching
                if (!searching) vm.docQuery = ""
            }) { Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    DocSort.values().forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, fontWeight = if (s == vm.docSort) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { vm.docSort = s; sortMenu = false }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                OutlinedTextField(
                    value = vm.docQuery,
                    onValueChange = { vm.docQuery = it },
                    singleLine = true,
                    placeholder = { Text("Search documents") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp)
                )
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.documents.isEmpty()) "No saved scans yet.\nScanned PDFs and JPEGs appear here."
                        else "No documents match your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.uri.toString() }) { doc ->
                        DocRow(
                            vm, doc,
                            onOpen = { openUri(ctx, doc.uri, doc.mime) },
                            onShare = { shareUris(ctx, listOf(doc.uri), doc.mime) },
                            onRename = { renameTarget = doc },
                            onDelete = { deleteTarget = doc }
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { item ->
        var text by remember(item.uri) { mutableStateOf(item.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) vm.renameDocument(item, text)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete document?") },
            text = { Text("\"${item.name}\" will be permanently deleted from your phone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteDocument(item); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DocRow(
    vm: AppViewModel,
    doc: DocItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val thumb by produceState<ImageBitmap?>(null, doc.uri, doc.modified) { value = vm.docThumb(doc) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(10.dp)).background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) Image(t, doc.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.PictureAsPdf, null)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (if (doc.isPdf) "PDF" else "JPEG") + " · " + formatSize(doc.size) + " · " + formatDate(doc.modified),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share") }
                IconButton(onClick = onRename) { Icon(Icons.Rounded.DriveFileRenameOutline, "Rename") }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
            }
        }
    }
}
