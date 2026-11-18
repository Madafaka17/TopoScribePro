package com.toposcribe.app.ui.theme

import androidx.compose.ui.graphics.Color

// Tokens tomados directamente del prototipo Stitch (ver plan: coinciden con los nombres de
// androidx.compose.material3.ColorScheme). primaryContainer es más oscuro/saturado que primary
// -- invertido respecto a la convención M3 habitual -- se respeta tal cual lo definió el diseño.
val LightPrimary = Color(0xFFA33900)
val LightPrimaryContainer = Color(0xFFCC4900)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightOnPrimaryContainer = Color(0xFFFFFFFF)

val LightSecondary = Color(0xFF006398)
val LightSecondaryContainer = Color(0xFF5BB8FE)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightOnSecondaryContainer = Color(0xFF0B1C30)

val LightSurface = Color(0xFFF8F9FF)
val LightSurfaceDim = Color(0xFFCBDBF5)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFEFF4FF)
val LightSurfaceContainer = Color(0xFFE5EEFF)
val LightSurfaceContainerHigh = Color(0xFFDCE9FF)
val LightSurfaceContainerHighest = Color(0xFFCEDCF7) // no provisto por el diseño, derivado por contraste
val LightOnSurface = Color(0xFF0B1C30)
val LightOnSurfaceVariant = Color(0xFF5A4138)

val LightOutline = Color(0xFF8E7166)
val LightOutlineVariant = Color(0xFFE2BFB2)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6) // no provisto, heurística M3 estándar
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFF8F9FF)
val LightOnBackground = Color(0xFF0B1C30)

val LightInversePrimary = Color(0xFFFFB68C)
val LightInverseSurface = Color(0xFF1E2A3A)
val LightInverseOnSurface = Color(0xFFEFF1FA)
val LightScrim = Color(0xFF000000)

// Dark theme: no provisto por el diseño -- derivado con la heurística M3 estándar (mismo
// hue/croma por rol, tonos invertidos). Punto de partida razonable, a ajustar a ojo.
val DarkPrimary = Color(0xFFFFB68C)
val DarkPrimaryContainer = Color(0xFF7A2900)
val DarkOnPrimary = Color(0xFF551F00)
val DarkOnPrimaryContainer = Color(0xFFFFDBC8)

val DarkSecondary = Color(0xFF8FCCFF)
val DarkSecondaryContainer = Color(0xFF004C77)
val DarkOnSecondary = Color(0xFF00334E)
val DarkOnSecondaryContainer = Color(0xFFCDE8FF)

val DarkSurface = Color(0xFF10151F)
val DarkSurfaceDim = Color(0xFF10151F)
val DarkSurfaceContainerLowest = Color(0xFF0B0F17)
val DarkSurfaceContainerLow = Color(0xFF181E2A)
val DarkSurfaceContainer = Color(0xFF1C2330)
val DarkSurfaceContainerHigh = Color(0xFF272E3C)
val DarkSurfaceContainerHighest = Color(0xFF323948)
val DarkOnSurface = Color(0xFFE2E6F0)
val DarkOnSurfaceVariant = Color(0xFFD6C3BB)

val DarkOutline = Color(0xFF9F8B82)
val DarkOutlineVariant = Color(0xFF4F3C33)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF10151F)
val DarkOnBackground = Color(0xFFE2E6F0)

val DarkInversePrimary = Color(0xFFA33900)
val DarkInverseSurface = Color(0xFFE2E6F0)
val DarkInverseOnSurface = Color(0xFF10151F)
val DarkScrim = Color(0xFF000000)
