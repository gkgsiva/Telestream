package com.telestream.app.ui.common

import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** True when running on Android TV (D-pad navigation, QR login, larger layout). */
val LocalIsTv = staticCompositionLocalOf { false }

private val Brand = Color(0xFF2AABEE)

private val colors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Color(0xFF8BD3F7),
    background = Color(0xFF0F1419),
    surface = Color(0xFF151C23),
    surfaceVariant = Color(0xFF1E2731),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF9AA7B4),
)

@Composable
fun TelestreamTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isTv = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    CompositionLocalProvider(LocalIsTv provides isTv) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
