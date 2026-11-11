package com.kowsiq.creativescan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kowsiq.creativescan.ui.screens.AboutScreen
import com.kowsiq.creativescan.ui.screens.CameraScreen
import com.kowsiq.creativescan.ui.screens.DocumentsScreen
import com.kowsiq.creativescan.ui.screens.EditScreen
import com.kowsiq.creativescan.ui.screens.HomeScreen
import com.kowsiq.creativescan.ui.screens.PagesScreen
import com.kowsiq.creativescan.ui.screens.PrivacyScreen
import com.kowsiq.creativescan.ui.screens.SettingsScreen

@Composable
fun AppRoot(vm: AppViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = vm.stack.size > 1) { vm.pop() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = vm.stack.last(),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "screens"
        ) { screen ->
            when (screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.CAMERA -> CameraScreen(vm)
                Screen.EDIT -> EditScreen(vm)
                Screen.PAGES -> PagesScreen(vm)
                Screen.DOCS -> DocumentsScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.ABOUT -> AboutScreen(vm)
                Screen.PRIVACY -> PrivacyScreen(vm)
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))

        vm.busy?.let { text ->
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(text)
                    }
                }
            }
        }

        vm.exportResult?.let { r ->
            AlertDialog(
                onDismissRequest = { vm.exportResult = null },
                title = { Text("Saved") },
                text = { Column { Text(r.summary) } },
                confirmButton = {
                    Row {
                        TextButton(onClick = { openUri(ctx, r.uris.first(), r.mime) }) { Text("Open") }
                        TextButton(onClick = { shareUris(ctx, r.uris, r.mime) }) { Text("Share") }
                    }
                },
                dismissButton = { TextButton(onClick = { vm.exportResult = null }) { Text("Close") } }
            )
        }
    }
}
