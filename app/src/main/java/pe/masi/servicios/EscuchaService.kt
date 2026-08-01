package pe.masi.servicios

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import pe.masi.motor.MotorMasi
import pe.masi.motor.OrdenContenido
import pe.masi.motor.ParserTolerante
import pe.masi.motor.Prompts
import pe.masi.motor.Rol

private const val TAG = "MasiEscucha"

/**
 * Tope de espera del ESCUCHA.
 *
 * Con clips de 5–8 s el primer token llega en unos segundos. 45 s es holgado; si se agota, algo va
 * mal de verdad y es mejor pedirle al niño que repita que dejarlo mirando una pantalla muerta.
 */
private const val TIMEOUT_MS = 45_000L

@Serializable data class TranscripcionLiteral(val dicho: String = "")

data class ResultadoEscucha(
  /** Lo que el modelo dijo haber oído. Vacío si falló. */
  val transcripcion: String,
  val lectura: ResultadoLectura,
) {
  val hayErrores: Boolean
    get() = lectura.errores.isNotEmpty()
}

/**
 * Agente ESCUCHA: audio del niño + texto esperado → qué palabras falló.
 *
 * Este es el agente crítico y el diferencial del proyecto. El problema de fondo:
 *
 * Un modelo de reconocimiento de voz está entrenado para producir la transcripción *más probable*,
 * y eso incluye un modelo de lenguaje implícito. Cuando el niño lee "El bero corre", el modelo oye
 * algo ambiguo en un contexto donde "perro" es abrumadoramente más probable — y escribe "perro".
 * **Corrige exactamente el error que necesitamos detectar.** No es un fallo: es lo que se le pidió.
 *
 * Las cuatro capas de mitigación, y dónde vive cada una:
 *  1. Prompt que fuerza transcripción literal, con el texto esperado en la primera línea → aquí.
 *  2. Segmentos cortos, de 10 palabras como mucho → [Segmentador] y los topes de `GrabadorAudio`.
 *  3. Comparación en código determinista → `DetectorErrores`, nunca el modelo.
 *  4. Umbral conservador → `PoliticaConservadora`.
 */
class EscuchaService(private val motor: MotorMasi) : Transcriptor {

  /**
   * @param textoEsperado la frase (o la palabra) que había en pantalla.
   * @param audioWav el clip, ya con cabecera WAV, a 16 kHz mono.
   * @param orden el orden de contenido a probar. Cambiarlo es todo el experimento A/B.
   */
  override suspend fun escuchar(
    textoEsperado: String,
    audioWav: ByteArray,
    orden: OrdenContenido,
  ): ResultadoEscucha {
    if (audioWav.isEmpty()) {
      return ResultadoEscucha("", ResultadoLectura(emptyList(), emptyList(), false))
    }

    val bruto =
      withTimeoutOrNull(TIMEOUT_MS) {
        motor.generarCompleto(
          rol = Rol.ESCUCHA,
          texto = Prompts.turnoEscucha(textoEsperado),
          audioWav = audioWav,
          orden = orden,
        )
      }

    if (bruto == null) {
      Log.w(TAG, "El ESCUCHA agotó el tiempo de espera")
      return ResultadoEscucha("", ResultadoLectura(emptyList(), emptyList(), false))
    }

    // Si el JSON viene torcido se intenta rescatar el texto suelto antes de rendirse: un modelo
    // pequeño a veces responde solo con la frase transcrita, sin envolverla.
    val dicho =
      ParserTolerante.parsear<TranscripcionLiteral>(bruto)?.dicho ?: rescatarTextoSuelto(bruto)

    Log.d(TAG, "Esperado: '$textoEsperado' | Transcrito: '$dicho'")

    // La comparación NO la hace el modelo. Aquí solo llega texto.
    return ResultadoEscucha(
      transcripcion = dicho,
      lectura = PoliticaConservadora.evaluar(textoEsperado, dicho),
    )
  }

  /**
   * Último recurso: si no hubo JSON pero sí una línea de texto plano corta y sin adornos, se toma
   * como la transcripción. Si tiene pinta de explicación del modelo, se descarta.
   */
  private fun rescatarTextoSuelto(bruto: String): String {
    val limpio = bruto.trim().removeSurrounding("\"").trim()
    if (limpio.isEmpty() || limpio.length > 200) return ""
    if (limpio.contains('{') || limpio.contains('\n')) return ""
    return limpio
  }
}
