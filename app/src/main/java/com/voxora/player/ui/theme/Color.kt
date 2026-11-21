package com.voxora.player.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// =========================================================================
// VOXORA Cinema Spatial (v2.6.0) — Pure OLED Obsidian + Sunset Amber-Coral
// =========================================================================

// Layered Obsidian & Frosted Glass Surfaces
val VoxoraObsidian = Color(0xFF050608)        // Pure OLED Obsidian canvas
val VoxoraDeepBlack = Color(0xFF000000)       // Pitch Black AMOLED
val VoxoraCharcoal = Color(0xFF0B0D13)        // Elevated dark sheet / drawer backdrop
val VoxoraCardCharcoal = Color(0xFF11131B)    // Frosted glass card surface
val VoxoraElevatedCharcoal = Color(0xFF181B26) // Raised pills & interactive tiles
val VoxoraOutlineDark = Color(0xFF1F2433)     // Subtle 0.8dp hairline border
val VoxoraHairlineBorder = Color(0xFF1F2433)

// Glassmorphism Overlay & Dock Surfaces
val VoxoraGlassDark = Color(0xE011131B)       // Frosted glass HUD / floating dock
val VoxoraGlassDrawer = Color(0xEB0B0D13)     // Translucent right-side player drawer
val VoxoraGlassBorder = Color(0x26FFFFFF)     // Specular glass top-edge highlight

// Signature Sunset Amber-to-Coral Gradient Accent (#F59E0B -> #F97316 -> #EC4899)
val VoxoraSunsetAmber = Color(0xFFF59E0B)
val VoxoraSunsetOrange = Color(0xFFF97316)
val VoxoraSunsetCoral = Color(0xFFEC4899)

val VoxoraSunsetGradientColors = listOf(
    VoxoraSunsetAmber,
    VoxoraSunsetOrange,
    VoxoraSunsetCoral
)

val VoxoraSunsetBrush = Brush.linearGradient(colors = VoxoraSunsetGradientColors)
val VoxoraSunsetHorizontalBrush = Brush.horizontalGradient(colors = VoxoraSunsetGradientColors)
val VoxoraSunsetSubtleBrush = Brush.linearGradient(
    colors = listOf(
        VoxoraSunsetAmber.copy(alpha = 0.22f),
        VoxoraSunsetOrange.copy(alpha = 0.20f),
        VoxoraSunsetCoral.copy(alpha = 0.22f)
    )
)

// Primary Accent System harmonized to Sunset Amber-Coral + Pure White
val VoxoraAmber = VoxoraSunsetAmber
val VoxoraAmberBright = Color(0xFFFFB74D)
val VoxoraAmberDim = Color(0xFFD97706)
val VoxoraAmberContainer = Color(0xFF2B1B0D)
val VoxoraPureWhite = Color(0xFFFFFFFF)

// Harmonized legacy semantic aliases
val VoxoraElectricPurple = VoxoraSunsetOrange
val VoxoraElectricPurpleBright = VoxoraAmberBright
val VoxoraElectricPurpleDim = VoxoraAmberDim
val VoxoraCyan = VoxoraAmberBright
val VoxoraSubtleBlue = Color(0xFFFDA4AF)
val VoxoraDeepBlue = VoxoraAmberDim

// Typography Colors (Dark Obsidian)
val VoxoraTextPrimary = Color(0xFFFFFFFF)
val VoxoraTextSecondary = Color(0xFFA0A7B8)
val VoxoraTextMuted = Color(0xFF6B7285)

// Studio Light Theme Palette
val VoxoraLightBackground = Color(0xFFF6F6F8)
val VoxoraLightSurface = Color(0xFFFFFFFF)
val VoxoraLightSurfaceVariant = Color(0xFFECEEF3)
val VoxoraLightOutline = Color(0xFFD8DCE6)
val VoxoraLightTextPrimary = Color(0xFF0E1016)
val VoxoraLightTextSecondary = Color(0xFF555D70)

// Semantic Status
val VoxoraError = Color(0xFFEF4444)
val VoxoraSuccess = Color(0xFF10B981)
val VoxoraWarning = Color(0xFFF5A623)
