package pe.masi.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MasiVoz"

/**
 * La voz de Masi.
 *
 * No es un adorno de accesibilidad: el usuario tiene 6 años y no sabe leer bien: **esa es
 * exactamente la razón por la que existe la app**. Si para usar la app que le enseña a leer hiciera
 * falta leer, el diseño habría fallado. Así que toda pantalla se locuta al entrar y toda respuesta
 * del TUTOR se escucha, además de verse.
 *
 * El `TextToSpeech` de Android es gratis, funciona sin conexión y ya está en el teléfono.
 */
class VozMasi(context: Context) {

  private val _listo = MutableStateFlow(false)
  val listo: StateFlow<Boolean> = _listo.asStateFlow()

  private val _hablando = MutableStateFlow(false)
  val hablando: StateFlow<Boolean> = _hablando.asStateFlow()

  private var tts: TextToSpeech? = null

  init {
    tts =
      TextToSpeech(context.applicationContext) { estado ->
        if (estado == TextToSpeech.SUCCESS) {
          _listo.value = configurarIdioma()
        } else {
          Log.w(TAG, "No hay motor de texto a voz en este dispositivo")
          _listo.value = false
        }
      }

    tts?.setOnUtteranceProgressListener(
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
          _hablando.value = true
        }

        override fun onDone(utteranceId: String?) {
          _hablando.value = false
        }

        @Deprecated("Reemplazado por onError(String, Int)", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
          _hablando.value = false
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
          _hablando.value = false
        }
      }
    )
  }

  /**
   * Español peruano si el teléfono lo tiene; si no, cualquier español antes que rendirse.
   *
   * Un motor con voz de España leyendo a un niño de Huancavelica es peor que ideal, pero es
   * infinitamente mejor que el silencio.
   */
  private fun configurarIdioma(): Boolean {
    val motor = tts ?: return false
    val candidatos = listOf(
        Locale.forLanguageTag("es-PE"),
        Locale.forLanguageTag("es-US"),
        Locale.forLanguageTag("es-ES"),
        Locale.forLanguageTag("es"),
      )
    for (locale in candidatos) {
      val resultado = motor.setLanguage(locale)
      if (
        resultado != TextToSpeech.LANG_MISSING_DATA && resultado != TextToSpeech.LANG_NOT_SUPPORTED
      ) {
        Log.i(TAG, "Voz configurada en $locale")
        motor.setSpeechRate(VELOCIDAD_NORMAL)
        return true
      }
    }
    Log.w(TAG, "El motor de voz no tiene español instalado")
    return false
  }

  /**
   * Dice el texto, cortando lo que estuviera diciendo.
   *
   * Los guiones de separación silábica se quitan antes de hablar. En pantalla "pe-rro" ayuda a
   * ver la estructura de la palabra; leído en voz alta, el motor de voz lo deletrea y suena
   * absurdo. Se ve separado, se oye entero.
   */
  fun decir(texto: String, id: String = "masi") {
    val limpio = sinGuionesSilabicos(texto)
    if (limpio.isBlank()) return
    tts?.speak(limpio, TextToSpeech.QUEUE_FLUSH, null, id)
  }

  /** Encola sin cortar lo anterior. Para decir dos cosas seguidas con una pausa entre medias. */
  fun encolar(texto: String, id: String = "masi_cola") {
    val limpio = sinGuionesSilabicos(texto)
    if (limpio.isBlank()) return
    tts?.speak(limpio, TextToSpeech.QUEUE_ADD, null, id)
  }

  /**
   * Dice una palabra: primero normal y luego despacio.
   *
   * La versión anterior leía las sílabas sueltas ("pe", "rro") y el resultado era peor que inútil:
   * ningún motor de voz sabe pronunciar "rro" fuera de una palabra, y lo deletreaba como
   * "erre erre o". Repetir la palabra entera a media velocidad transmite la misma información —los
   * sonidos, separados en el tiempo— sin pedirle al motor algo que no sabe hacer.
   */
  fun decirPalabra(palabra: String) {
    val limpia = sinGuionesSilabicos(palabra)
    if (limpia.isBlank()) return
    val motor = tts ?: return
    motor.setSpeechRate(VELOCIDAD_NORMAL)
    motor.speak(limpia, TextToSpeech.QUEUE_FLUSH, null, "palabra")
    motor.playSilentUtterance(350, TextToSpeech.QUEUE_ADD, "pausa")
    motor.setSpeechRate(VELOCIDAD_LENTA)
    motor.speak(limpia, TextToSpeech.QUEUE_ADD, null, "palabra_lenta")
    motor.setSpeechRate(VELOCIDAD_NORMAL)
  }

  /**
   * Quita los guiones que separan sílabas.
   *
   * Solo los que van entre dos letras, para no tocar guiones sueltos ni de puntuación. Una palabra
   * compuesta con guion perdería el suyo, pero en textos de primaria no aparecen y la alternativa
   * —oír "pe guion rro"— es mucho peor.
   */
  private fun sinGuionesSilabicos(texto: String): String =
    texto.replace(Regex("""(?<=\p{L})-(?=\p{L})"""), "")

  companion object {
    /** Ya un poco por debajo de lo normal: el niño necesita oír cada sílaba. */
    const val VELOCIDAD_NORMAL = 0.9f

    /** Para la segunda pasada de [decirPalabra]. */
    const val VELOCIDAD_LENTA = 0.55f
  }

  fun callar() {
    tts?.stop()
    _hablando.value = false
  }

  /** Hay que llamarlo al morir la pantalla o el proceso: el motor de voz es un servicio del sistema. */
  fun liberar() {
    try {
      tts?.stop()
      tts?.shutdown()
    } catch (e: Exception) {
      Log.w(TAG, "Falló el cierre del motor de voz", e)
    }
    tts = null
    _listo.value = false
    _hablando.value = false
  }
}
