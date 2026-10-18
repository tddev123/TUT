package com.example.tut.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.tut.data.BlockColor
import com.example.tut.data.RirLevel

object TutColors {
    val Background = Color(0xFF0E1111)
    val Surface = Color(0xFF1A1F1F)
    val SurfaceHigh = Color(0xFF252B2B)
    val Outline = Color(0xFF3A4242)
    val Text = Color(0xFFECEFEF)
    val Muted = Color(0xFF9AA5A5)
    val Green = Color(0xFF3DDC84)
    val Blue = Color(0xFF4A9EFF)
    val GreenDark = Color(0xFF1E6B43)
    val Red = Color(0xFFFF5A5A)
    val RedDark = Color(0xFFB3261E)
    val Black = Color(0xFF000000)
    /** Warning sign / outline on black (overtraining) blocks, which would vanish on the dark background. */
    val Warning = Color(0xFFFFC21A)
    val Orange = Color(0xFFFFA23A)
    val Gold = Color(0xFFE6B422)
    val GoldLight = Color(0xFFFFD95A)
    val GoldLighter = Color(0xFFFFEDA8)
    val Gray = Color(0xFF8C9696)
}

fun BlockColor?.toColor(): Color = when (this) {
    // Streak steps 2, 3, 4+: medium gold, light gold, lighter gold.
    BlockColor.GOLD -> TutColors.GoldLighter
    BlockColor.GOLD_MEDIUM -> TutColors.GoldLight
    BlockColor.GOLD_DARK -> TutColors.Gold
    BlockColor.GREEN -> TutColors.Green
    BlockColor.GRAY -> TutColors.Gray
    BlockColor.RED -> TutColors.Red
    BlockColor.RED_DARK -> TutColors.RedDark
    BlockColor.BLACK -> TutColors.Black
    null -> TutColors.Outline
}

/** For text, lines and outlines: like [toColor], but black (overtraining) shows as the warning colour. */
fun BlockColor?.inkColor(): Color = if (this == BlockColor.BLACK) TutColors.Warning else toColor()

fun RirLevel?.toColor(): Color = when (this) {
    RirLevel.HIGH -> TutColors.Green
    RirLevel.MODERATE -> TutColors.Orange
    RirLevel.LOW -> TutColors.Red
    null -> TutColors.Muted
}

@Composable
fun TutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TutColors.Green,
            onPrimary = Color.Black,
            secondary = TutColors.Green,
            background = TutColors.Background,
            onBackground = TutColors.Text,
            surface = TutColors.Surface,
            onSurface = TutColors.Text,
            surfaceVariant = TutColors.SurfaceHigh,
            onSurfaceVariant = TutColors.Muted,
            surfaceContainerHigh = TutColors.SurfaceHigh,
            surfaceContainer = TutColors.Surface,
            outline = TutColors.Outline,
            error = TutColors.Red,
        ),
        content = content,
    )
}
