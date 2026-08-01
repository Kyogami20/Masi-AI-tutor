package pe.masi.servicios

import pe.masi.demo.DatosPrecocinados
import pe.masi.motor.OrdenContenido

/**
 * Qué pasó cuando el niño leyó en voz alta.
 *
 * Nótese que no hay ningún estado que signifique "error" a secas. O leyó bien, o hay una palabra
 * concreta con una pista concreta, o no se le entendió. Nunca un veredicto negativo suelto.
 */
sealed interface Veredicto {
  data object Acierto : Veredicto

  /** Una palabra salió distinta, con la pista del TUTOR ya lista para decirse en voz alta. */
  data class Falla(val error: ErrorLectura, val pista: String, val silabas: String) : Veredicto

  /**
   * La transcripción no es de fiar. **No es un fallo del niño y jamás se le presenta como tal.**
   *
   * Es la salida de la [PoliticaConservadora]: ante la duda no se marca error, se pide repetir.
   * Dejar pasar un error real es preferible a inventar uno que no ocurrió.
   */
  data object NoSeEntendio : Veredicto
}

/**
 * Oír al niño y decir qué se entendió. Lo implementa [EscuchaService] con el modelo.
 *
 * Las dos interfaces de este archivo existen para que la decisión que toma [EvaluadorPronunciacion]
 * —la más delicada del proyecto: cuándo se marca un error y cuándo no— sea comprobable sin cargar
 * 2,6 GB de modelo ni un `Context` de Android.
 */
fun interface Transcriptor {
  suspend fun escuchar(
    textoEsperado: String,
    audioWav: ByteArray,
    orden: OrdenContenido,
  ): ResultadoEscucha
}

/** Convertir un error en una pista amable. Lo implementa [TutorService]. */
fun interface Explicador {
  suspend fun explicar(error: ErrorLectura): Pista
}

/**
 * El bucle audio → veredicto, en un solo sitio.
 *
 * Existe porque **dos pantallas necesitan exactamente esto**: la de Escuchar, con un fragmento de la
 * página, y la de Practicar, con una palabra de una tarjeta. Antes vivía suelto dentro del
 * ViewModel de Escuchar; duplicarlo habría significado que cualquier ajuste al umbral conservador
 * hubiera que hacerlo en dos sitios y acordarse de los dos.
 *
 * Encadena las cuatro capas de mitigación en orden: el [Transcriptor] transcribe literalmente, la
 * [PoliticaConservadora] decide si esa transcripción merece confianza, [Silabas] separa la palabra
 * y el [Explicador] la explica. Ninguna comparación la hace el modelo.
 */
class EvaluadorPronunciacion(
  private val escucha: Transcriptor,
  private val tutor: Explicador,
) {

  /**
   * @param objetivo lo que estaba escrito y el niño debía leer: un fragmento o una palabra suelta.
   * @param demo si true, se responde con datos precocinados y no se toca el modelo.
   */
  suspend fun evaluar(
    objetivo: String,
    audioWav: ByteArray,
    orden: OrdenContenido = OrdenContenido.GALLERY,
    demo: Boolean = false,
  ): Veredicto {
    if (audioWav.isEmpty()) return Veredicto.NoSeEntendio

    val resultado =
      if (demo) DatosPrecocinados.escuchar(objetivo) else escucha.escuchar(objetivo, audioWav, orden)

    if (!resultado.lectura.transcripcionFiable) return Veredicto.NoSeEntendio

    val error = resultado.lectura.errores.firstOrNull() ?: return Veredicto.Acierto

    val silabas = Silabas.separar(error.esperado)
    val pista =
      if (demo) DatosPrecocinados.explicar(error) else tutor.explicar(error).texto
    return Veredicto.Falla(error, pista, silabas)
  }
}
