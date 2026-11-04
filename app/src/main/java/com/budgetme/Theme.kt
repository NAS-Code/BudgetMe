package com.budgetme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

// Fixed BudgetMe green (wallpaper colors came out grey). Tones follow Material 3's tonal palette for #2E7D5B.
private val Light = lightColorScheme(
    primary = Color(0xFF2E7D5B), onPrimary = Color.White, primaryContainer = Color(0xFFB4F1D0), onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357), onSecondary = Color.White, secondaryContainer = Color(0xFFCFE9D8), onSecondaryContainer = Color(0xFF0A1F15),
    tertiary = Color(0xFF3D6373), onTertiary = Color.White, tertiaryContainer = Color(0xFFC1E8FB), onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF6FBF6), onBackground = Color(0xFF171D19), surface = Color(0xFFF6FBF6), onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DD), onSurfaceVariant = Color(0xFF404943), outline = Color(0xFF707973), outlineVariant = Color(0xFFC0C9C1),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F5F0), surfaceContainer = Color(0xFFEAEFEA),
    surfaceContainerHigh = Color(0xFFE4EAE5), surfaceContainerHighest = Color(0xFFDFE4DF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF98D5B4), onPrimary = Color(0xFF003824), primaryContainer = Color(0xFF0F5137), onPrimaryContainer = Color(0xFFB4F1D0),
    secondary = Color(0xFFB3CCBD), onSecondary = Color(0xFF1F352A), secondaryContainer = Color(0xFF354B40), onSecondaryContainer = Color(0xFFCFE9D8),
    tertiary = Color(0xFFA5CCDF), onTertiary = Color(0xFF073543), tertiaryContainer = Color(0xFF244C5A), onTertiaryContainer = Color(0xFFC1E8FB),
    background = Color(0xFF0F1511), onBackground = Color(0xFFDFE4DF), surface = Color(0xFF0F1511), onSurface = Color(0xFFDFE4DF),
    surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFBFC9C1), outline = Color(0xFF8A938C), outlineVariant = Color(0xFF404943),
    surfaceContainerLowest = Color(0xFF0A0F0C), surfaceContainerLow = Color(0xFF171D19), surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27), surfaceContainerHighest = Color(0xFF303632),
)

// Tabular figures everywhere, so amounts line up in columns.
private fun TextStyle.tnum() = copy(fontFeatureSettings = "tnum")
private val Type = Typography().run {
    copy(displayLarge.tnum(), displayMedium.tnum(), displaySmall.tnum(), headlineLarge.tnum(), headlineMedium.tnum(), headlineSmall.tnum(),
        titleLarge.tnum(), titleMedium.tnum(), titleSmall.tnum(), bodyLarge.tnum(), bodyMedium.tnum(), bodySmall.tnum(),
        labelLarge.tnum(), labelMedium.tnum(), labelSmall.tnum())
}

@Composable
fun BudgetTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)

/** Budget bar color: green on track, amber from 90% of the budget, red when over. */
@Composable
fun budgetColor(spent: Long, budget: Long): Color = when {
    spent > budget -> MaterialTheme.colorScheme.error
    spent * 10 >= budget * 9 -> if (isSystemInDarkTheme()) Color(0xFFF0BE5A) else Color(0xFFA86A00)
    else -> MaterialTheme.colorScheme.primary
}
