package pe.masi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// -------------------------------------------------------------------------------------------------
// Color
//
// Fondo crema, nunca blanco puro: el blanco a máximo brillo deslumbra y cansa, y estos niños van a
// pasar minutos mirando texto. Contraste alto sobre fondo cálido.
// -------------------------------------------------------------------------------------------------

val Crema = Color(0xFFFDF6E3)
val CremaOscura = Color(0xFFF3E8CE)
val Tinta = Color(0xFF3A3226)
val Terracota = Color(0xFFC1502E)
val TerracotaClara = Color(0xFFE8A08A)
val VerdeLogro = Color(0xFF2E7D5B)
val AzulCalma = Color(0xFF2F5FA8)

private val esquemaMasi =
  lightColorScheme(
    primary = Terracota,
    onPrimary = Color.White,
    primaryContainer = TerracotaClara,
    onPrimaryContainer = Tinta,
    secondary = AzulCalma,
    onSecondary = Color.White,
    tertiary = VerdeLogro,
    onTertiary = Color.White,
    background = Crema,
    onBackground = Tinta,
    surface = Crema,
    onSurface = Tinta,
    surfaceVariant = CremaOscura,
    onSurfaceVariant = Tinta,
    outline = Color(0xFFBFAE8E),
    // No hay "error" en la paleta de Masi. Nada se pinta de rojo de fallo: ni cruces, ni avisos
    // en rojo, ni semáforos. Un niño que se cree malo leyendo deja de intentarlo.
    error = AzulCalma,
    onError = Color.White,
  )

// -------------------------------------------------------------------------------------------------
// Tipografía
//
// Lo que dice la evidencia, y es una de las decisiones de las que este proyecto está más orgulloso:
// las fuentes "para dislexia" NO funcionan. El estudio de Wery y Diliberto (Annals of Dyslexia,
// 2017) comparó OpenDyslexic contra Arial y Times New Roman y no encontró mejora ninguna en
// velocidad ni en precisión — y ningún participante prefirió esa fuente. Un estudio de 2018 sobre
// Dyslexie llegó a lo mismo. La razón de fondo: cuatro décadas de investigación sitúan el origen de
// la dislexia en el procesamiento fonológico, no en un déficit visual. Una fuente no arregla un
// problema que no es de forma de las letras.
//
// Lo que SÍ tiene evidencia es el espaciado aumentado entre letras y entre líneas, una sans-serif
// normal, y la práctica fonológica sistemática. Eso es lo que hay aquí.
// -------------------------------------------------------------------------------------------------

/**
 * Para usar Atkinson Hyperlegible: dejar `AtkinsonHyperlegible-Regular.ttf` en `res/font/` y
 * cambiar esta constante por el `FontFamily` correspondiente. Con la sans-serif del sistema el
 * espaciado ya hace casi todo el trabajo.
 */
val FuenteMasi = FontFamily.SansSerif

/** Separación entre letras. Esto sí tiene evidencia detrás. */
val ESPACIADO_LETRAS = 0.12.em

/** Interlineado. Idem. */
val ALTURA_LINEA = 1.8.em

private val tipografiaMasi =
  Typography(
    // El texto que el niño lee en voz alta. Grande de verdad.
    displayLarge =
      TextStyle(
        fontFamily = FuenteMasi,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = ALTURA_LINEA,
        letterSpacing = ESPACIADO_LETRAS,
      ),
    displayMedium =
      TextStyle(
        fontFamily = FuenteMasi,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = ALTURA_LINEA,
        letterSpacing = ESPACIADO_LETRAS,
      ),
    headlineMedium =
      TextStyle(
        fontFamily = FuenteMasi,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = ALTURA_LINEA,
        letterSpacing = ESPACIADO_LETRAS,
      ),
    // Nunca por debajo de 24sp en nada que lea el niño.
    bodyLarge =
      TextStyle(
        fontFamily = FuenteMasi,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = ALTURA_LINEA,
        letterSpacing = ESPACIADO_LETRAS,
      ),
    bodyMedium =
      TextStyle(
        fontFamily = FuenteMasi,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = ALTURA_LINEA,
        letterSpacing = ESPACIADO_LETRAS,
      ),
    // Solo para las pantallas de adulto. El niño no ve este tamaño nunca.
    labelMedium =
      TextStyle(
        fontFamily = FuenteMasi,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.02.em,
      ),
  )

/** true si el niño prefiere OpenDyslexic. Es opción suya, nunca el valor por defecto. */
val LocalPrefiereOpenDyslexic = compositionLocalOf { false }

@Composable
fun TemaMasi(prefiereOpenDyslexic: Boolean = false, contenido: @Composable () -> Unit) {
  // Sin tema oscuro a propósito: el fondo crema es parte del diseño de lectura, y una segunda
  // paleta sería una segunda superficie de fallo el día de la demo.
  @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()

  CompositionLocalProvider(LocalPrefiereOpenDyslexic provides prefiereOpenDyslexic) {
    MaterialTheme(colorScheme = esquemaMasi, typography = tipografiaMasi, content = contenido)
  }
}
