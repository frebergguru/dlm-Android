package guru.freberg.dlm.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand fallback palette (used pre-Android 12, where dynamic color is absent).
// A calm indigo with a teal accent — friendly and high-contrast.
private val Indigo = Color(0xFF4655CA)
private val IndigoLightContainer = Color(0xFFDFE0FF)
private val IndigoOnLightContainer = Color(0xFF000C5F)
private val IndigoDark = Color(0xFFBCC2FF)
private val IndigoDarkContainer = Color(0xFF2C3AAE)

// Status accents for download states.
val SuccessGreen = Color(0xFF2E7D32)
val SuccessGreenDark = Color(0xFF7BD67E)
val WarningAmber = Color(0xFFB26A00)
val WarningAmberDark = Color(0xFFFFB951)

val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoLightContainer,
    onPrimaryContainer = IndigoOnLightContainer,
    secondary = Color(0xFF5A5D72),
    tertiary = Color(0xFF00696E),
)

val DarkColors = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF0A1781),
    primaryContainer = IndigoDarkContainer,
    onPrimaryContainer = IndigoLightContainer,
    secondary = Color(0xFFC2C5DD),
    tertiary = Color(0xFF4CD9E0),
)
