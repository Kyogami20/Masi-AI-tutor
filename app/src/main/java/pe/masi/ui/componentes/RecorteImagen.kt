package pe.masi.ui.componentes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import kotlin.math.max
import kotlin.math.min

/**
 * Fracción del alto y del ancho que ocupa el marco por defecto.
 *
 * Es el mismo rectángulo que se dibuja como guía en la cámara, así que lo que el niño encuadró es
 * lo que se recorta. Empieza generoso: es preferible que sobre un poco de mesa a que se corte una
 * línea del texto.
 */
object MarcoPorDefecto {
  const val ANCHO = 0.92f
  const val ALTO = 0.72f

  /** El rectángulo por defecto, en coordenadas relativas (0..1) sobre la imagen. */
  fun relativo(): Rect {
    val x = (1f - ANCHO) / 2f
    val y = (1f - ALTO) / 2f
    return Rect(offset = Offset(x, y), size = Size(ANCHO, ALTO))
  }
}

private const val LADO_MINIMO = 0.2f

/**
 * La foto con un rectángulo de recorte que se puede arrastrar y redimensionar.
 *
 * Recortar antes de mandar la imagen al modelo no es cosmética. El encoder de visión gasta un
 * presupuesto **fijo** de tokens sea cual sea el tamaño de la foto: si la mitad de la imagen es
 * mesa, mano y el borde de la otra página, la mitad de ese presupuesto se va en describir cosas
 * que no son texto. Recortando, los mismos tokens describen solo la página — mejor transcripción
 * al mismo coste — y además el modelo deja de transcribir texto que no interesa, lo que recorta
 * directamente los segundos de generación.
 *
 * @param onRecorteCambiado recibe el rectángulo en coordenadas relativas (0..1) sobre la imagen.
 */
@Composable
fun RecorteImagen(
  imagen: ImageBitmap,
  recorte: Rect,
  onRecorteCambiado: (Rect) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier) {
    val anchoCaja = constraints.maxWidth.toFloat()
    val altoCaja = constraints.maxHeight.toFloat()

    // La imagen se dibuja con ContentScale.Fit, así que hay que calcular dónde queda de verdad
    // dentro de la caja para que el rectángulo de recorte caiga encima y no al lado.
    val escala = min(anchoCaja / imagen.width, altoCaja / imagen.height)
    val anchoImagen = imagen.width * escala
    val altoImagen = imagen.height * escala
    val izquierda = (anchoCaja - anchoImagen) / 2f
    val arriba = (altoCaja - altoImagen) / 2f

    // El bloque de `pointerInput` es una corrutina de larga vida: se crea una vez y sobrevive a las
    // recomposiciones. Eso significa que **captura los valores del momento en que se creó**, así
    // que leer `recorte` directamente dentro del gesto daría siempre el rectángulo inicial: cada
    // `onDrag` calcularía "inicial + delta de este frame" y se perdería todo lo acumulado. El
    // rectángulo se quedaba temblando a un dedo del origen.
    //
    // `rememberUpdatedState` es la forma estándar de resolverlo: la lambda captura un objeto State
    // estable, y leerlo devuelve siempre el valor de la composición más reciente.
    val recorteActual by rememberUpdatedState(recorte)
    val alCambiar by rememberUpdatedState(onRecorteCambiado)

    Image(
      bitmap = imagen,
      contentDescription = "La foto que acabas de tomar",
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize(),
    )

    Canvas(
      modifier =
        // La clave son las dimensiones, que es lo único que invalida de verdad el cálculo de
        // píxeles. Antes era la propia imagen, y como el llamante creaba un ImageBitmap nuevo en
        // cada recomposición, el gesto se reiniciaba —y se cancelaba— a mitad del arrastre.
        Modifier.fillMaxSize().pointerInput(anchoImagen, altoImagen) {
          // Local al gesto: no se pinta, así que no tiene por qué provocar recomposiciones.
          var arrastrando = Asa.NINGUNA
          detectDragGestures(
            onDragStart = { punto ->
              val r = aPixeles(recorteActual, izquierda, arriba, anchoImagen, altoImagen)
              arrastrando = asaMasCercana(punto, r)
            },
            onDragEnd = { arrastrando = Asa.NINGUNA },
            onDragCancel = { arrastrando = Asa.NINGUNA },
            onDrag = { cambio, delta ->
              if (arrastrando == Asa.NINGUNA) return@detectDragGestures
              cambio.consume()
              val dx = delta.x / anchoImagen
              val dy = delta.y / altoImagen
              alCambiar(mover(recorteActual, arrastrando, dx, dy))
            },
          )
        }
    ) {
      val r = aPixeles(recorte, izquierda, arriba, anchoImagen, altoImagen)
      dibujarVelo(r)
      drawRect(
        color = Color.White,
        topLeft = r.topLeft,
        size = r.size,
        style = Stroke(width = 4.dp.toPx()),
      )
      // Asas gordas: el dedo de un niño no acierta en un punto de 8 dp.
      val radio = 16.dp.toPx()
      listOf(r.topLeft, Offset(r.right, r.top), Offset(r.left, r.bottom), r.bottomRight).forEach {
        drawCircle(color = Color.White, radius = radio, center = it)
      }
    }
  }
}

private enum class Asa {
  NINGUNA,
  SUP_IZQ,
  SUP_DER,
  INF_IZQ,
  INF_DER,
  DENTRO,
}

private fun aPixeles(r: Rect, izq: Float, arr: Float, ancho: Float, alto: Float): Rect =
  Rect(
    left = izq + r.left * ancho,
    top = arr + r.top * alto,
    right = izq + r.right * ancho,
    bottom = arr + r.bottom * alto,
  )

private fun asaMasCercana(punto: Offset, r: Rect): Asa {
  val umbral = 90f
  val esquinas =
    listOf(
      Asa.SUP_IZQ to r.topLeft,
      Asa.SUP_DER to Offset(r.right, r.top),
      Asa.INF_IZQ to Offset(r.left, r.bottom),
      Asa.INF_DER to r.bottomRight,
    )
  val cercana = esquinas.minByOrNull { (_, p) -> (p - punto).getDistance() }
  if (cercana != null && (cercana.second - punto).getDistance() < umbral) return cercana.first
  return if (r.contains(punto)) Asa.DENTRO else Asa.NINGUNA
}

private fun mover(r: Rect, asa: Asa, dx: Float, dy: Float): Rect {
  fun limitar(v: Float) = v.coerceIn(0f, 1f)
  return when (asa) {
    Asa.NINGUNA -> r
    Asa.DENTRO -> {
      val nx = (r.left + dx).coerceIn(0f, 1f - r.width)
      val ny = (r.top + dy).coerceIn(0f, 1f - r.height)
      Rect(nx, ny, nx + r.width, ny + r.height)
    }
    Asa.SUP_IZQ ->
      Rect(
        min(limitar(r.left + dx), r.right - LADO_MINIMO),
        min(limitar(r.top + dy), r.bottom - LADO_MINIMO),
        r.right,
        r.bottom,
      )
    Asa.SUP_DER ->
      Rect(
        r.left,
        min(limitar(r.top + dy), r.bottom - LADO_MINIMO),
        max(limitar(r.right + dx), r.left + LADO_MINIMO),
        r.bottom,
      )
    Asa.INF_IZQ ->
      Rect(
        min(limitar(r.left + dx), r.right - LADO_MINIMO),
        r.top,
        r.right,
        max(limitar(r.bottom + dy), r.top + LADO_MINIMO),
      )
    Asa.INF_DER ->
      Rect(
        r.left,
        r.top,
        max(limitar(r.right + dx), r.left + LADO_MINIMO),
        max(limitar(r.bottom + dy), r.top + LADO_MINIMO),
      )
  }
}

/** Oscurece lo que queda fuera del recorte, para que se vea de un golpe qué se va a usar. */
private fun DrawScope.dibujarVelo(r: Rect) {
  val velo = Color.Black.copy(alpha = 0.5f)
  drawRect(velo, topLeft = Offset.Zero, size = Size(size.width, r.top))
  drawRect(velo, topLeft = Offset(0f, r.bottom), size = Size(size.width, size.height - r.bottom))
  drawRect(velo, topLeft = Offset(0f, r.top), size = Size(r.left, r.height))
  drawRect(
    velo,
    topLeft = Offset(r.right, r.top),
    size = Size(size.width - r.right, r.height),
  )
}

/** Marco fijo que se dibuja encima de la cámara, con las mismas proporciones que el recorte. */
@Composable
fun MarcoGuia(modifier: Modifier = Modifier) {
  Box(modifier = modifier.background(Color.Transparent)) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val r = MarcoPorDefecto.relativo()
      val rect =
        Rect(
          left = r.left * size.width,
          top = r.top * size.height,
          right = r.right * size.width,
          bottom = r.bottom * size.height,
        )
      dibujarVelo(rect)
      drawRect(
        color = Color.White,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = 4.dp.toPx()),
      )
    }
  }
}
