package com.kowsiq.creativescan.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kowsiq.creativescan.data.DocItem
import com.kowsiq.creativescan.ui.AppViewModel
import com.kowsiq.creativescan.ui.BrandMark
import com.kowsiq.creativescan.ui.Screen
import com.kowsiq.creativescan.ui.rememberEnsureStorage
import com.kowsiq.creativescan.ui.theme.Cyan
import com.kowsiq.creativescan.ui.theme.Navy

@Composable
fun HomeScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val ensureStorage = rememberEnsureStorage()
    LaunchedEffect(Unit) { ensureStorage { vm.loadDocuments() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Navy)) { append("Creative Scan") }
                        withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = Cyan)) { append(" by Kowsiq") }
                    },
                    fontSize = 22.sp
                )
                Text(
                    "Professional Document Scanner",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // Primary action
        Card(
            onClick = { vm.startScan() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Navy)
        ) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Cyan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.DocumentScanner, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Scan Document", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Camera with automatic edge detection", color = Color(0xFFB9C7E4), fontSize = 13.sp)
                }
            }
        }

        if (vm.pages.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                onClick = { vm.navigate(Screen.PAGES) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, tint = Navy)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Continue current scan (${vm.pages.size} page${if (vm.pages.size == 1) "" else "s"})",
                        color = Navy, fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        ActionCard(
            Icons.Rounded.CreditCard, "ID Card Scan", "Capture front and back on one A4 page",
            Modifier.fillMaxWidth()
        ) { vm.startIdCardScan() }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionCard(
                Icons.Rounded.PhotoLibrary, "Import from Gallery", "Scan existing photos",
                Modifier.weight(1f)
            ) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            ActionCard(
                Icons.Rounded.History, "Recent Scans", "Latest exports",
                Modifier.weight(1f)
            ) { vm.openDocuments(recent = true) }
        }
        Spacer(Modifier.height(14.dp))
        ActionCard(
            Icons.Rounded.Folder, "My Documents", "Search, rename, share and delete",
            Modifier.fillMaxWidth()
        ) { vm.openDocuments(recent = false) }

        val recent = vm.documents.sortedByDescending { it.modified }.take(8)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Recent", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.uri.toString() }) { doc ->
                    RecentThumb(vm, doc) { vm.openDocuments(recent = true) }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { vm.navigate(Screen.SETTINGS) }) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Settings")
            }
            TextButton(onClick = { vm.navigate(Screen.ABOUT) }) {
                Icon(Icons.Rounded.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("About")
            }
            TextButton(onClick = { vm.navigate(Screen.PRIVACY) }) {
                Icon(Icons.Rounded.PrivacyTip, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Privacy")
            }
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp)) {
            Icon(icon, null, tint = Navy, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, color = Navy)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentThumb(vm: AppViewModel, doc: DocItem, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, doc.uri, doc.modified) { value = vm.docThumb(doc) }
    Box(
        Modifier.size(width = 84.dp, height = 112.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        thumb?.let {
            Image(it, doc.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
