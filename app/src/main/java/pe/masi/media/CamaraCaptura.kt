package pe.masi.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private const val TAG = "MasiCamara"

/**
 * Lado máximo de la foto que se le manda al modelo, en píxeles.
 *
 * Es el equilibrio entre que el OCR lea bien la letra pequeña de un libro de primaria y que el
 * encoder de visión no tarde una eternidad. Si alguna página no se transcribe bien, subirlo es lo
 * primero que hay que probar; cuesta latencia.
 */
const val LADO_MAXIMO_FOTO = 1024

/** Recuerda al usuario de CameraX que hay que soltarlo, y expone el disparador. */
class ControlCamara {
  internal var imageCapture: ImageCapture? = null

  val listo: Boolean
    get() = imageCapture != null

  /**
   * Dispara la foto y devuelve los bytes PNG ya rotados y reescalados.
   *
   * Sin recortar: el recorte lo decide el niño en la pantalla de confirmación.
   *
   * @param onFoto se llama en un hilo de fondo. Recibe `null` si la captura falló.
   */
  fun disparar(onFoto: (ByteArray?) -> Unit) {
    val captura = imageCapture
    if (captura == null) {
      onFoto(null)
      return
    }
    captura.takePicture(
      Executors.newSingleThreadExecutor(),
      object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          try {
            val bitmap = image.toBitmap().rotar(image.imageInfo.rotationDegrees).reescalar()
            onFoto(bitmap.aPng())
          } catch (e: Exception) {
            Log.e(TAG, "Falló el procesado de la foto", e)
            onFoto(null)
          } finally {
            image.close()
          }
        }

        override fun onError(exception: ImageCaptureException) {
          Log.e(TAG, "Falló la captura", exception)
          onFoto(null)
        }
      },
    )
  }
}

/**
 * Vista previa de la cámara trasera.
 *
 * Dos detalles que importan:
 *
 * **El recuadro se ve entero.** `FIT_CENTER` en vez del relleno por defecto: así lo que el niño ve
 * en el visor es exactamente lo que va a salir en la foto, y el marco de guía cae sobre la misma
 * zona que luego se recorta. Con el modo de relleno, el visor recorta por su cuenta y la guía
 * mentía.
 *
 * **Se puede fotografiar en horizontal.** La interfaz está fija en vertical porque para un niño es
 * más simple, pero una página de libro suele ser apaisada. Un `OrientationEventListener` avisa a
 * CameraX de cómo se está sujetando el teléfono de verdad, y la foto sale derecha aunque la app no
 * gire.
 */
@Composable
fun VistaCamara(control: ControlCamara, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val previewUseCase = remember { Preview.Builder().build() }
  val captureUseCase = remember {
    // Se pide una resolución alta: la letra de un libro de primaria no perdona.
    val estrategia =
      ResolutionStrategy(
        Size(1536, 2048),
        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
      )
    ImageCapture.Builder()
      .setResolutionSelector(
        ResolutionSelector.Builder()
          .setResolutionStrategy(estrategia)
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .build()
      )
      .build()
  }

  var proveedor by remember { mutableStateOf<ProcessCameraProvider?>(null) }
  val vistaPrevia = remember {
    PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
  }
  val vigilanteRotacion = remember { VigilanteRotacion(context, captureUseCase) }

  LaunchedEffect(Unit) { proveedor = ProcessCameraProvider.awaitInstance(context) }

  // El enlace se hace UNA vez, cuando el proveedor está listo.
  //
  // Antes esto vivía en el bloque `update` de AndroidView, que Compose ejecuta en cada
  // recomposición: cada vez soltaba la cámara con unbindAll() y la volvía a enlazar, y el visor se
  // quedaba negro varios segundos mientras tanto. Es un fallo clásico y engañoso, porque la app no
  // peta ni escupe nada en el log: simplemente parece que la cámara "se cuelga".
  LaunchedEffect(proveedor) {
    val p = proveedor ?: return@LaunchedEffect
    try {
      previewUseCase.surfaceProvider = vistaPrevia.surfaceProvider
      p.unbindAll()
      p.bindToLifecycle(
        lifecycleOwner,
        CameraSelector.DEFAULT_BACK_CAMERA,
        previewUseCase,
        captureUseCase,
      )
      control.imageCapture = captureUseCase
    } catch (e: Exception) {
      Log.e(TAG, "No se pudo enlazar la cámara", e)
    }
  }

  DisposableEffect(Unit) {
    vigilanteRotacion.enable()
    onDispose {
      vigilanteRotacion.disable()
      proveedor?.unbindAll()
      control.imageCapture = null
    }
  }

  Box(modifier = modifier) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { vistaPrevia })
  }
}

/**
 * Le dice a CameraX cómo se está sujetando el teléfono.
 *
 * La actividad está bloqueada en vertical, así que Android nunca informa de un cambio de rotación.
 * Sin esto, una foto tomada con el teléfono de lado sale girada 90° y el modelo intenta leer un
 * texto tumbado, que es justo lo peor que se le puede dar a un OCR.
 */
private class VigilanteRotacion(context: Context, private val captura: ImageCapture) :
  OrientationEventListener(context) {

  override fun onOrientationChanged(orientation: Int) {
    if (orientation == ORIENTATION_UNKNOWN) return
    val rotacion =
      when (orientation) {
        in 45 until 135 -> Surface.ROTATION_270
        in 135 until 225 -> Surface.ROTATION_180
        in 225 until 315 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
      }
    if (captura.targetRotation != rotacion) captura.targetRotation = rotacion
  }
}

fun Bitmap.rotar(grados: Int): Bitmap {
  if (grados == 0) return this
  val matriz = Matrix().apply { postRotate(grados.toFloat()) }
  return Bitmap.createBitmap(this, 0, 0, width, height, matriz, true)
}

/** Reduce la foto para que su lado mayor sea [LADO_MAXIMO_FOTO], conservando la proporción. */
fun Bitmap.reescalar(ladoMaximo: Int = LADO_MAXIMO_FOTO): Bitmap {
  val mayor = maxOf(width, height)
  if (mayor <= ladoMaximo) return this
  val escala = ladoMaximo.toFloat() / mayor
  return Bitmap.createScaledBitmap(this, (width * escala).toInt(), (height * escala).toInt(), true)
}

/**
 * Recorta el bitmap con un rectángulo en coordenadas relativas (0..1).
 *
 * Devuelve el original si el recorte es degenerado, para que un gesto raro no deje al niño sin
 * foto.
 */
fun Bitmap.recortar(relativo: Rect): Bitmap {
  val x = (relativo.left * width).toInt().coerceIn(0, width - 1)
  val y = (relativo.top * height).toInt().coerceIn(0, height - 1)
  val ancho = (relativo.width * width).toInt().coerceIn(1, width - x)
  val alto = (relativo.height * height).toInt().coerceIn(1, height - y)
  if (ancho < 32 || alto < 32) return this
  return Bitmap.createBitmap(this, x, y, ancho, alto)
}

/** PNG, sin pérdidas: el JPEG mancharía justo los bordes de las letras que hay que reconocer. */
fun Bitmap.aPng(): ByteArray {
  val salida = ByteArrayOutputStream()
  compress(Bitmap.CompressFormat.PNG, 100, salida)
  return salida.toByteArray()
}
