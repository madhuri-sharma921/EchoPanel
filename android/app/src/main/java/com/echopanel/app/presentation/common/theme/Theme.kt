package com.echopanel.app.presentation.common.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp


private val Indigo90 = Color(0xFF1B1F3B)
private val Indigo70 = Color(0xFF2E3564)
private val Indigo50 = Color(0xFF4A548F)
private val Indigo30 = Color(0xFF8890C4)
private val Indigo10 = Color(0xFFE7E9FA)

private val Amber60 = Color(0xFFE8A33D)
private val Amber80 = Color(0xFFFFD8A0)

private val Slate99 = Color(0xFFFBFAFF)
private val Slate10 = Color(0xFF121218)

private val Rose60 = Color(0xFFC4485A)

private val LightColors = lightColorScheme(
    primary = Indigo70,
    onPrimary = Color.White,
    primaryContainer = Indigo10,
    onPrimaryContainer = Indigo90,
    secondary = Amber60,
    onSecondary = Color.White,
    secondaryContainer = Amber80,
    onSecondaryContainer = Indigo90,
    tertiary = Indigo50,
    background = Slate99,
    surface = Color.White,
    surfaceVariant = Indigo10,
    error = Rose60,
    errorContainer = Color(0xFFFBE2E4),
    onErrorContainer = Rose60,
)

private val DarkColors = darkColorScheme(
    primary = Indigo30,
    onPrimary = Indigo90,
    primaryContainer = Indigo70,
    onPrimaryContainer = Indigo10,
    secondary = Amber60,
    onSecondary = Indigo90,
    secondaryContainer = Color(0xFF5C4420),
    onSecondaryContainer = Amber80,
    tertiary = Indigo30,
    background = Slate10,
    surface = Color(0xFF1C1C24),
    surfaceVariant = Indigo70,
    error = Color(0xFFE8828F),
    errorContainer = Color(0xFF4A2126),
    onErrorContainer = Color(0xFFE8828F),
)

private val EchoPanelTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun EchoPanelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = EchoPanelTypography, content = content)
}
