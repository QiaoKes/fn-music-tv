package com.fnmusic.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object FnColors {
    val Background = Color(0xFF101214)
    val Surface = Color(0xFF171A1E)
    val Text = Color(0xFFF4F2EC)
    val Muted = Color(0xFFA9ADB4)
    val Coral = Color(0xFFFF7657)
    val Teal = Color(0xFF55C5A5)
    val Warning = Color(0xFFE8C36A)
}

@Composable
fun FnMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FnColors.Coral,
            secondary = FnColors.Teal,
            background = FnColors.Background,
            surface = FnColors.Surface,
            onPrimary = FnColors.Background,
            onSecondary = FnColors.Background,
            onBackground = FnColors.Text,
            onSurface = FnColors.Text,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides FnColors.Text, content = content)
    }
}
