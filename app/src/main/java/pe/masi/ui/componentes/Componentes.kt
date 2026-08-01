package pe.masi.ui.componentes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.masi.ui.theme.Crema
import pe.masi.ui.theme.CremaOscura

/**
 * Tamaños del botón grande según cuántos quepan en una fila.
 *
 * No es un capricho de diseño: con tres botones de 140 dp hacen falta 460 dp de ancho y un teléfono
 * normal tiene 360. El tercer botón acababa aplastado contra el borde con la etiqueta partida en
 * una letra por línea. Elegir el tamaño según el número de botones evita que eso vuelva a pasar.
 */
object TamanoBoton {
  val UNO: Dp = 140.dp
  val DOS: Dp = 128.dp
  val TRES: Dp = 92.dp

  fun para(cuantos: Int): Dp =
    when {
      cuantos <= 1 -> UNO
      cuantos == 2 -> DOS
      else -> TRES
    }
}

/**
 * El botón de Masi: grande, con icono, y con la etiqueta debajo por si el adulto mira.
 *
 * El icono es quien lleva el significado. El niño no puede leer —esa es la razón de que exista la
 * app— así que si un botón solo se entiende leyéndolo, el botón está mal.
 */
@Composable
fun BotonGrande(
  icono: ImageVector,
  etiqueta: String,
  descripcion: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
  habilitado: Boolean = true,
  tamano: Dp = TamanoBoton.UNO,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    // Ancho fijo igual al del círculo: así la etiqueta se parte en palabras y no en letras.
    modifier = modifier.width(tamano),
  ) {
    Surface(
      onClick = onClick,
      enabled = habilitado,
      shape = CircleShape,
      color = if (habilitado) color else MaterialTheme.colorScheme.outline,
      modifier = Modifier.size(tamano).semantics { contentDescription = descripcion },
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icono,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(tamano * 0.5f),
        )
      }
    }
    Text(
      text = etiqueta,
      style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (tamano < 110.dp) 16.sp else 20.sp),
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center,
      maxLines = 2,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** Fila de botones que se dimensionan solos para caber. */
@Composable
fun FilaDeBotones(
  cuantos: Int,
  modifier: Modifier = Modifier,
  contenido: @Composable (Dp) -> Unit,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.Top,
  ) {
    contenido(TamanoBoton.para(cuantos))
  }
}

/**
 * La barra de abajo, fija y siempre en el mismo sitio.
 *
 * Existe porque los botones de acción y las flechas de navegación se pisaban cuando el contenido
 * crecía. Separar "qué hago con esto" (en el cuerpo) de "a dónde voy" (aquí abajo) es lo que hacen
 * las apps serias, y de paso el niño encuentra siempre el mismo sitio para volver.
 */
@Composable
fun BarraInferior(
  onAnterior: (() -> Unit)? = null,
  onSiguiente: (() -> Unit)? = null,
  hayAnterior: Boolean = true,
  haySiguiente: Boolean = true,
  etiquetaCentro: String? = null,
  onCentro: (() -> Unit)? = null,
) {
  Surface(color = CremaOscura, tonalElevation = 2.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FlechaNavegacion(
        icono = Icons.AutoMirrored.Rounded.ArrowBack,
        descripcion = "Anterior",
        onClick = onAnterior,
        habilitada = hayAnterior,
      )

      if (etiquetaCentro != null && onCentro != null) {
        TextButton(onClick = onCentro) {
          Text(
            etiquetaCentro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      } else {
        Spacer(Modifier.width(48.dp))
      }

      FlechaNavegacion(
        icono = Icons.AutoMirrored.Rounded.ArrowForward,
        descripcion = "Siguiente",
        onClick = onSiguiente,
        habilitada = haySiguiente,
      )
    }
  }
}

@Composable
private fun FlechaNavegacion(
  icono: ImageVector,
  descripcion: String,
  onClick: (() -> Unit)?,
  habilitada: Boolean,
) {
  if (onClick == null) {
    Spacer(Modifier.size(56.dp))
    return
  }
  IconButton(onClick = onClick, enabled = habilitada, modifier = Modifier.size(56.dp)) {
    Icon(
      icono,
      contentDescription = descripcion,
      modifier = Modifier.size(32.dp),
      tint =
        if (habilitada) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )
  }
}

/** Línea de contexto de arriba: "3 de 12", "Palabra 1 de 4". Nunca instrucciones. */
@Composable
fun EncabezadoContexto(texto: String, accion: (@Composable () -> Unit)? = null) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = texto,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.outline,
    )
    accion?.invoke()
  }
}

/**
 * El estado intermedio, animado, que acompaña cada llamada al modelo.
 *
 * No es decoración: es requisito. Cargar el motor tarda hasta 10 segundos y una página entera puede
 * tardar un minuto en transcribirse. Un niño de 7 años abandona con 3 segundos de pantalla muerta,
 * así que la interfaz nunca espera al modelo en silencio.
 */
@Composable
fun EstadoCargando(mensaje: String, detalle: String? = null, modifier: Modifier = Modifier) {
  val transicion = rememberInfiniteTransition(label = "carga")
  val fase by
    transicion.animateFloat(
      initialValue = 0f,
      targetValue = 3f,
      animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
      label = "fase",
    )

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp),
    modifier = modifier.padding(24.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      repeat(3) { i ->
        val activo = fase.toInt() == i
        Box(
          modifier =
            Modifier.size(24.dp)
              .scale(if (activo) 1.4f else 1f)
              .clip(CircleShape)
              .background(
                if (activo) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer
              )
        )
      }
    }
    Text(
      text = mensaje,
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onBackground,
    )
    // Señal de vida real: cuántas palabras lleva leídas. Ver una cifra que sube es la diferencia
    // entre "está trabajando" y "se colgó".
    if (detalle != null) {
      Text(
        text = detalle,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}

/**
 * El texto de lectura: grande, muy espaciado y sobre fondo cálido.
 *
 * El espaciado aumentado entre letras y entre líneas es de lo poco que tiene evidencia sólida a
 * favor para lectores con dificultades. Aquí no es un ajuste, es el diseño.
 */
@Composable
fun TextoAdaptado(texto: String, modifier: Modifier = Modifier, tamano: Int = 32) {
  Surface(
    color = CremaOscura,
    shape = RoundedCornerShape(20.dp),
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = texto,
      style =
        MaterialTheme.typography.displayMedium.copy(
          fontSize = tamano.sp,
          color = MaterialTheme.colorScheme.onSurface,
        ),
      modifier = Modifier.padding(20.dp),
    )
  }
}

/** Una palabra sola, grande, para el modo palabra por palabra y para las tarjetas. */
@Composable
fun PalabraGigante(palabra: String, silabas: String?, modifier: Modifier = Modifier) {
  // El tamaño baja con la longitud: "contribuye" a 64sp se partía en dos líneas por la mitad.
  val tamano =
    when {
      palabra.length <= 6 -> 60
      palabra.length <= 9 -> 48
      palabra.length <= 12 -> 38
      else -> 30
    }
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = palabra,
      style = MaterialTheme.typography.displayLarge.copy(fontSize = tamano.sp),
      textAlign = TextAlign.Center,
      maxLines = 1,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.fillMaxWidth(),
    )
    if (silabas != null && silabas != palabra) {
      Text(
        text = silabas,
        style = MaterialTheme.typography.displayMedium.copy(fontSize = (tamano * 0.62f).sp),
        textAlign = TextAlign.Center,
        maxLines = 1,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/** Barra de progreso simple para la descarga del modelo. */
@Composable
fun BarraProgreso(fraccion: Float, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp)).background(CremaOscura)
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth(fraccion.coerceIn(0f, 1f))
          .height(20.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.primary)
    )
  }
}

/** Separación vertical, para no repetir Spacer(Modifier.height(...)) por todas partes. */
@Composable
fun Hueco(alto: Int) {
  Spacer(Modifier.height(alto.dp))
}

/** El fondo de todas las pantallas de Masi. Crema, nunca blanco. */
@Composable
fun FondoMasi(modifier: Modifier = Modifier, contenido: @Composable () -> Unit) {
  Surface(color = Crema, modifier = modifier) { contenido() }
}

/**
 * El esqueleto común: encabezado arriba, contenido en medio, barra fija abajo.
 *
 * Que todas las pantallas compartan estructura no es solo estética: significa que el botón de
 * volver y las flechas están siempre en el mismo píxel, y para un niño que no lee, la constancia
 * espacial es la mitad de la usabilidad.
 */
@Composable
fun PantallaMasi(
  encabezado: String? = null,
  accionEncabezado: (@Composable () -> Unit)? = null,
  barraInferior: (@Composable () -> Unit)? = null,
  contenido: @Composable (PaddingValues) -> Unit,
) {
  FondoMasi(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      if (encabezado != null) EncabezadoContexto(encabezado, accionEncabezado)
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) { contenido(PaddingValues(0.dp)) }
      barraInferior?.invoke()
    }
  }
}
