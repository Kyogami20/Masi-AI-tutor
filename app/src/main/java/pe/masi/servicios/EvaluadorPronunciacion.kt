package pe.masi.servicios

import pe.masi.demo.DatosPrecocinados
import pe.masi.diagnostico.CajaNegra
import pe.masi.motor.OrdenContenido

/**
 * Qué pasó cuando el niño leyó en voz alta.
 *
 * Nótese que no hay ningún estado que signifique "error" a secas. O leyó bien, o hay una palabra
 * concreta con una pista concreta, o no se le entendió. Nunca un veredicto negativo suelto.
 */
/**
 * Una palabra que salió distinta, lista para convertirse en tarjeta.
 *
 * @param escritura como está en el libro: "estableció". Es lo que ve el niño.
 * @param clave la forma normalizada: "establecio". Es lo que deduplica.
 */
data class PalabraFallada(
  val escritura: String,
  val clave: String,
  val silabas: String,
  val pista: String,
  val error: ErrorLectura,
)

sealed interface Veredicto {
  data object Acierto : Veredicto

  /**
   * Una o varias palabras salieron distintas.
   *
   * **Es una lista, y eso importa.** Antes era un error suelto porque el evaluador hacía
   * `errores.firstOrNull()`: en una oración con dos fallos, el segundo se descartaba en silencio y
   * nunca llegaba a ser tarjeta. Una oración de diez palabras da para fallar más de una.
   *
   * Nunca viene vacía: si no hay errores el veredicto es [Acierto]. El tope de cuántas caben lo
   * pone [PoliticaConservadora.maxDiscrepancias], que escala con la longitud de la frase.
   */
  data class Falla(val palabras: List<PalabraFallada>) : Veredicto

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
  private val memoria: MemoriaDeDudas = MemoriaDeDudas(),
) {

  /** Se llama al empezar una página nueva: una duda de ayer no dice nada de la frase de hoy. */
  fun olvidarDudas() = memoria.olvidar()

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

    val lectura = resultado.lectura

    if (!lectura.transcripcionFiable) {
      anotar(objetivo, resultado, lectura, emptyList())
      return Veredicto.NoSeEntendio
    }

    // Una duda descartada puede volverse evidencia si se repite. Ver [MemoriaDeDudas].
    val confirmados = memoria.confirmarRepetidos(objetivo, lectura.descartados)
    val errores = lectura.errores + confirmados
    anotar(objetivo, resultado, lectura, confirmados)

    if (errores.isEmpty()) {
      // **Aquí estaba el fallo**, y era de lógica, no de umbral: se devolvía `Acierto` sin mirar si
      // la lista estaba vacía porque el niño leyó bien o porque se había descartado todo por dudoso.
      // Un niño que leía "cuaderno" donde decía "libro" recibía un "¡Muy bien!".
      //
      // Sin errores y sin dudas, leyó bien. Sin errores pero con dudas, lo honesto es pedir que
      // repita: el compromiso del proyecto era "ante la duda no acuses", nunca "ante la duda
      // aplaude".
      return if (lectura.hayDudas) Veredicto.NoSeEntendio else Veredicto.Acierto
    }

    // Cómo está escrita cada palabra del texto, alineado 1:1 con lo que comparó el detector.
    val original = DetectorErrores.palabrasConOriginal(objetivo)

    return Veredicto.Falla(
      errores.mapIndexed { posicion, error ->
        val escritura = escrituraDe(error, original)

        PalabraFallada(
          escritura = escritura,
          clave = error.esperado,
          silabas = Silabas.separar(escritura),
          // Solo la primera palabra estrena pista del TUTOR. Ver [pistaPara].
          pista = pistaPara(error, primera = posicion == 0, demo = demo),
          error = error,
        )
      }
    )
  }

  /**
   * Cómo se escribe de verdad la palabra que se falló: "mamá", no "mama".
   *
   * **Se busca por forma normalizada, no por el índice del error**, y eso es a propósito. La primera
   * versión hacía `original[error.indice - 1]` suponiendo que el índice era 1-based. No lo era, y el
   * fallo fue de los peores: la guarda de seguridad detectaba que la palabra no cuadraba, caía al
   * respaldo, y la tarjeta salía igual pero **sin tilde**. Nada petó, nada se registró, y la app
   * estuvo enseñándole a un niño "mama" y "pesame" durante varias versiones.
   *
   * Emparejar por la forma normalizada no depende de convenios de índice. Este solo se usa para
   * desempatar cuando la palabra aparece más de una vez en el fragmento, y si falla ahí lo peor que
   * pasa es que se coja la otra aparición de la misma palabra, que se escribe igual.
   */
  private fun escrituraDe(error: ErrorLectura, original: List<PalabraOriginal>): String =
    original
      .withIndex()
      .filter { (_, p) -> p.normalizada == error.esperado }
      .minByOrNull { (i, _) -> kotlin.math.abs(i - error.indice) }
      ?.value
      ?.escritura ?: error.esperado

  /**
   * La pista de cada palabra fallada.
   *
   * **Solo la primera pasa por el modelo**, y es una decisión de tiempo, no de pereza. Cada llamada
   * al TUTOR son varios segundos, y encadenarlas dejaría al niño mirando una pantalla de carga
   * justo después de haber leído. Las demás usan la pista de repuesto, que está escrita a mano por
   * tipo de error y es instantánea.
   *
   * No se pierde nada: cuando esa palabra aparezca en Practicar, la pista se recalcula con el TUTOR
   * y con el error de ese día, que además es más pertinente que el de hoy.
   */
  private suspend fun pistaPara(error: ErrorLectura, primera: Boolean, demo: Boolean): String =
    when {
      demo -> DatosPrecocinados.explicar(error)
      primera -> tutor.explicar(error).texto
      else -> BancoDePistas.para(error, Silabas.separar(error.esperado))
    }

  /**
   * Deja constancia de qué se oyó y qué se decidió, para la pantalla de diagnóstico.
   *
   * **Es el dato que distingue dos fallos idénticos en pantalla**: que la política descartara un
   * error de verdad, o que el modelo autocorrigiera la transcripción y nunca llegara a haber nada
   * que descartar. Ver [pe.masi.diagnostico.Escuchado]. No sale del teléfono.
   */
  private fun anotar(
    objetivo: String,
    resultado: ResultadoEscucha,
    lectura: ResultadoLectura,
    confirmados: List<ErrorLectura>,
  ) {
    val decision =
      when {
        !lectura.transcripcionFiable -> "no me fié de lo que oí → pedí que repitiera"
        confirmados.isNotEmpty() ->
          "se repitió la misma sustitución → marcado: " + parejas(confirmados)
        lectura.errores.isNotEmpty() -> "marcado: " + parejas(lectura.errores)
        lectura.hayDudas ->
          "no lo tuve claro (" +
            lectura.descartados.filter { it.motivo.esDuda }.joinToString("; ") {
              "${it.error.esperado}→${it.error.dicho}, ${it.motivo.name.lowercase()}"
            } +
            ") → pedí que repitiera"
        else -> "leyó bien"
      }
    // Si al limpiar el eco cambió algo, se enseñan los dos textos: el crudo delata al modelo
    // divagando, y el comparado dice qué se juzgó de verdad. Un eco que el limpiador no reconozca
    // se ve aquí a simple vista en vez de quedarse invisible como estuvo hasta ahora.
    val transcrito =
      if (DetectorErrores.normalizar(resultado.transcripcion) == resultado.comparado) {
        resultado.transcripcion
      } else {
        resultado.transcripcion + "\n  (se comparó: \"" + resultado.comparado + "\")"
      }
    CajaNegra.anotarEscucha(objetivo, transcrito, decision)
  }

  private fun parejas(errores: List<ErrorLectura>): String =
    errores.joinToString("; ") { "${it.esperado}→${it.dicho}" }
}
