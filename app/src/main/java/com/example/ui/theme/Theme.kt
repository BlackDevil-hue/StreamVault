package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemePreference {
    SYSTEM,
    DARK,
    LIGHT
}

@Immutable
data class StreamVaultExtras(
    val isDark: Boolean,
    val warning: Color,
    val heroScrimStart: Color,
    val heroScrimEnd: Color,
    val heroAccentBorder: Color,
    val heroOnScrim: Color,
    val heroOnScrimMuted: Color
)

val LocalStreamVaultExtras = staticCompositionLocalOf {
    StreamVaultExtras(
        isDark = true,
        warning = AmberWarning,
        heroScrimStart = MediaScrimDark.copy(alpha = 0.90f),
        heroScrimEnd = MediaScrimDark.copy(alpha = 0.58f),
        heroAccentBorder = ElectricCyan.copy(alpha = 0.45f),
        heroOnScrim = MediaOverlayLightText,
        heroOnScrimMuted = MediaOverlayMutedText
    )
}

private val StreamVaultDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = OnElectricCyanContainer,
    secondary = CobaltIndigo,
    onSecondary = OnCobaltIndigo,
    secondaryContainer = CobaltIndigoContainer,
    onSecondaryContainer = OnCobaltIndigoContainer,
    tertiary = MintEmerald,
    onTertiary = OnMintEmerald,
    tertiaryContainer = MintEmeraldContainer,
    onTertiaryContainer = OnMintEmeraldContainer,
    background = ObsidianBg,
    onBackground = Color(0xFFF1F5F9),
    surface = ObsidianSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = CoralError,
    onError = OnCoralError,
    errorContainer = CoralErrorContainer,
    onErrorContainer = OnCoralErrorContainer
)

private val StreamVaultLightColorScheme = lightColorScheme(
    primary = DeepCyanPrimary,
    onPrimary = OnDeepCyanPrimary,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = OnDeepCyanContainer,
    secondary = DeepIndigoSecondary,
    onSecondary = OnDeepIndigoSecondary,
    secondaryContainer = DeepIndigoContainer,
    onSecondaryContainer = OnDeepIndigoContainer,
    tertiary = DeepEmeraldTertiary,
    onTertiary = OnDeepEmeraldTertiary,
    tertiaryContainer = DeepEmeraldContainer,
    onTertiaryContainer = OnDeepEmeraldContainer,
    background = SlateBg,
    onBackground = Color(0xFF0F172A),
    surface = SlateSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceElevated,
    surfaceContainerHigh = SlateSurfaceHigh,
    outline = SlateOutline,
    outlineVariant = SlateOutlineVariant,
    error = LightError,
    onError = OnLightError,
    errorContainer = LightErrorContainer,
    onErrorContainer = OnLightErrorContainer
)

val StreamVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun StreamVaultTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> StreamVaultDarkColorScheme
        else -> StreamVaultLightColorScheme
    }

    val extras = StreamVaultExtras(
        isDark = darkTheme,
        warning = AmberWarning,
        heroScrimStart = MediaScrimDark.copy(alpha = 0.90f),
        heroScrimEnd = MediaScrimDark.copy(alpha = 0.56f),
        heroAccentBorder = colorScheme.primary.copy(alpha = if (darkTheme) 0.42f else 0.35f),
        heroOnScrim = MediaOverlayLightText,
        heroOnScrimMuted = MediaOverlayMutedText
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalStreamVaultExtras provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StreamVaultTypography,
            shapes = StreamVaultShapes,
            content = content
        )
    }
}
