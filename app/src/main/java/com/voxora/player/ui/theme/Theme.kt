package com.voxora.player.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.voxora.player.model.ThemeMode

@Immutable
data class VoxoraExtendedColors(
    val blueHighlight: Color,
    val purpleGlow: Color,
    val cardElevated: Color,
    val cardBorder: Color,
    val glassSurface: Color,
    val textMuted: Color,
    val success: Color,
    val warning: Color
)

val LocalVoxoraColors = staticCompositionLocalOf {
    VoxoraExtendedColors(
        blueHighlight = VoxoraSubtleBlue,
        purpleGlow = VoxoraElectricPurpleBright,
        cardElevated = VoxoraElevatedCharcoal,
        cardBorder = VoxoraOutlineDark,
        glassSurface = Color(0xD9121218),
        textMuted = VoxoraTextMuted,
        success = VoxoraSuccess,
        warning = VoxoraWarning
    )
}

private val CharcoalDarkColorScheme = darkColorScheme(
    primary = VoxoraElectricPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2D1E5F),
    onPrimaryContainer = VoxoraElectricPurpleBright,
    secondary = VoxoraSubtleBlue,
    onSecondary = Color(0xFF041F2E),
    secondaryContainer = Color(0xFF132D42),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = VoxoraElectricPurpleBright,
    background = VoxoraObsidian,
    onBackground = VoxoraTextPrimary,
    surface = VoxoraCharcoal,
    onSurface = VoxoraTextPrimary,
    surfaceVariant = VoxoraCardCharcoal,
    onSurfaceVariant = VoxoraTextSecondary,
    surfaceContainerLowest = VoxoraDeepBlack,
    surfaceContainerLow = VoxoraCharcoal,
    surfaceContainer = VoxoraCardCharcoal,
    surfaceContainerHigh = VoxoraElevatedCharcoal,
    outline = VoxoraOutlineDark,
    error = VoxoraError,
    onError = Color.White
)

private val AmoledDarkColorScheme = CharcoalDarkColorScheme.copy(
    background = VoxoraDeepBlack,
    surface = VoxoraDeepBlack,
    surfaceContainerLowest = VoxoraDeepBlack,
    surfaceContainerLow = Color(0xFF0A0A0E),
    surfaceContainer = Color(0xFF111117),
    surfaceContainerHigh = Color(0xFF191922)
)

private val VoxoraLightColorScheme = lightColorScheme(
    primary = VoxoraElectricPurpleDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF3B0764),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    background = VoxoraLightBackground,
    onBackground = VoxoraLightTextPrimary,
    surface = VoxoraLightSurface,
    onSurface = VoxoraLightTextPrimary,
    surfaceVariant = VoxoraLightSurfaceVariant,
    onSurfaceVariant = VoxoraLightTextSecondary,
    surfaceContainerLowest = VoxoraLightSurface,
    surfaceContainerLow = VoxoraLightBackground,
    surfaceContainer = VoxoraLightSurface,
    surfaceContainerHigh = VoxoraLightSurfaceVariant,
    outline = VoxoraLightOutline,
    error = VoxoraError,
    onError = Color.White
)

private val VoxoraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VoxoraTheme(
    themeMode: ThemeMode = ThemeMode.DARK_CHARCOAL,
    content: @Composable () -> Unit
) {
    val isDark = themeMode != ThemeMode.LIGHT
    val colorScheme = when (themeMode) {
        ThemeMode.DARK_CHARCOAL -> CharcoalDarkColorScheme
        ThemeMode.AMOLED_BLACK -> AmoledDarkColorScheme
        ThemeMode.LIGHT -> VoxoraLightColorScheme
    }

    val extendedColors = when (themeMode) {
        ThemeMode.DARK_CHARCOAL -> VoxoraExtendedColors(
            blueHighlight = VoxoraSubtleBlue,
            purpleGlow = VoxoraElectricPurpleBright,
            cardElevated = VoxoraElevatedCharcoal,
            cardBorder = VoxoraOutlineDark,
            glassSurface = Color(0xE0121218),
            textMuted = VoxoraTextMuted,
            success = VoxoraSuccess,
            warning = VoxoraWarning
        )
        ThemeMode.AMOLED_BLACK -> VoxoraExtendedColors(
            blueHighlight = VoxoraSubtleBlue,
            purpleGlow = VoxoraElectricPurpleBright,
            cardElevated = Color(0xFF15151E),
            cardBorder = Color(0xFF232332),
            glassSurface = Color(0xE6050508),
            textMuted = VoxoraTextMuted,
            success = VoxoraSuccess,
            warning = VoxoraWarning
        )
        ThemeMode.LIGHT -> VoxoraExtendedColors(
            blueHighlight = Color(0xFF0284C7),
            purpleGlow = VoxoraElectricPurpleDim,
            cardElevated = Color(0xFFF0EFF8),
            cardBorder = VoxoraLightOutline,
            glassSurface = Color(0xE8FFFFFF),
            textMuted = Color(0xFF6B6B84),
            success = Color(0xFF059669),
            warning = Color(0xFFD97706)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalVoxoraColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VoxoraTypography,
            shapes = VoxoraShapes,
            content = content
        )
    }
}
