package pe.masi.demo

import kotlinx.coroutines.delay
import pe.masi.servicios.ErrorLectura
import pe.masi.servicios.Lectura
import pe.masi.servicios.PoliticaConservadora
import pe.masi.servicios.ResultadoEscucha
import pe.masi.servicios.ResultadoLector
import pe.masi.servicios.TipoError

/**
 * Modo demo: respuestas fijas, sin tocar el modelo.
 *
 * Esto no es una trampa, es un seguro. El día del evento el teléfono puede estar caliente, la
 * batería al 15 % y el motor tardando el triple de lo normal. Con este interruptor el recorrido
 * completo —foto, lectura, detección, pista, tarjeta— sigue funcionando delante del jurado.
 *
 * Se activa desde la pantalla de ajustes, que está detrás de una pulsación larga. Y si se usa
 * durante una demo, se dice en voz alta. La honestidad técnica genera confianza; que te pillen, no.
 */
object DatosPrecocinados {

  /** Latencia simulada, del orden de la real, para que la animación de carga se vea igual. */
  private const val ESPERA_VISION_MS = 3_500L
  private const val ESPERA_AUDIO_MS = 2_500L
  private const val ESPERA_TUTOR_MS = 1_500L

  /** Lleva las marcas `|` a propósito: es exactamente lo que devuelve el LECTOR de verdad. */
  private const val TEXTO_PAGINA =
    "El perro corre por el campo.|La niña juega con la pelota.|" +
      "Mi mamá compra pan en el mercado."

  suspend fun leerPagina(): ResultadoLector {
    delay(ESPERA_VISION_MS)
    // Pasa por el mismo constructor que la ruta real: si el troceado se rompe, la demo también,
    // y es mejor enterarse ensayando que delante del jurado.
    return ResultadoLector.Exito(Lectura.de(TEXTO_PAGINA))
  }

  /**
   * Simula que el niño leyó "bero" donde decía "perro" — el caso de la demo.
   *
   * Si la frase esperada no contiene "perro", se devuelve una lectura perfecta: es preferible que
   * el modo demo se quede corto a que invente un error donde el niño leyó bien.
   */
  suspend fun escuchar(textoEsperado: String): ResultadoEscucha {
    delay(ESPERA_AUDIO_MS)
    val transcripcion =
      if (textoEsperado.contains("perro", ignoreCase = true)) {
        textoEsperado.replace(Regex("(?i)perro"), "bero")
      } else {
        textoEsperado
      }
    return ResultadoEscucha(
      transcripcion = transcripcion,
      lectura = PoliticaConservadora.evaluar(textoEsperado, transcripcion),
    )
  }

  suspend fun explicar(error: ErrorLectura): String {
    delay(ESPERA_TUTOR_MS)
    return when (error.tipo) {
      TipoError.SUSTITUCION_INICIAL ->
        "¡Casi! Dice pe-rro. Mira la P: tiene su barriguita mirando adelante."
      TipoError.INVERSION -> "¡Muy bien! Están todas las letras, solo cambiaron de sitio."
      else -> "¡Buen intento! Vamos a decirla otra vez, despacito."
    }
  }
}
