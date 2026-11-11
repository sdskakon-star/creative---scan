package com.kowsiq.creativescan.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Navy = Color(0xFF0B1F4B)
val NavyLight = Color(0xFF1B3A7A)
val Cyan = Color(0xFF14B8D4)
val Mist = Color(0xFFF1F5FA)

@Composable
fun CreativeScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Navy,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE6F7),
            onPrimaryContainer = Navy,
            secondary = Cyan,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD3F3F9),
            onSecondaryContainer = Navy,
            background = Color.White,
            surface = Color.White,
            surfaceVariant = Mist,
            onSurface = Color(0xFF111827),
            onSurfaceVariant = Color(0xFF55627A),
            outline = Color(0xFFC5CFDB)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
