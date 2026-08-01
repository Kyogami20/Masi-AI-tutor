package pe.masi.servicios

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import pe.masi.datos.Cuento
import pe.masi.motor.MotorMasi
import pe.masi.motor.Prompts
import pe.masi.motor.Rol

private const val TAG = "MasiCuentista"

/** Por dónde va la generación. Es lo que se enseña durante la espera. */
enum class PasoCuento {
  ELIGIENDO_PALABRAS,
  ESCRIBIENDO,
  REVISANDO,
  GUARDANDO,
}

sealed interface ResultadoCuento {
  data class Exito(val cuento: Cuento, val lectura: Lectura) : ResultadoCuento

  /** El modelo respondió, pero lo que escribió no se le puede enseñar a un niño. */
  data class NoSirve(val motivo: MotivoRechazo) : ResultadoCuento

  /** No hay palabras que practicar todavía. */
  data object SinPalabras : ResultadoCuento

  data object NoSePudo : ResultadoCuento
}

/**
 * Escribe un cuento con las palabras que al niño le cuestan.
 *
 * **Esto llegó a estar hecho con function calling y se deshizo a propósito.** Conviene dejar escrito
 * el porqué, para que nadie lo rehaga pensando que es una mejora.
 *
 * El argumento era bueno sobre el papel: "a un modelo de 2B le pides cinco palabras y se deja dos,
 * así que hace falta una herramienta que compruebe y un bucle que reescriba". El bucle llegó a
 * funcionar en el teléfono. Pero el log decía esto:
 *
 * ```
 * Cuento generado. Palabras encargadas=3, comprobaciones=1 (3/3), guardó=false
 * ```
 *
 * Comprobó **una vez** y pasó a la primera. El bucle de reescritura no se disparó nunca, porque las
 * palabras que practica un niño de 7 años son sencillas y Gemma 4 las coloca sin ayuda. La
 * herramienta era un sello de goma.
 *
 * Y costaba calidad: el function calling quiere temperatura baja y decodificado restringido, que es
 * lo contrario de lo que necesita la prosa. Los cuentos salían telegráficos.
 *
 * Así que la verificación se queda —es buena y sigue siendo determinista— pero en Kotlin, después de
 * generar, con un único reintento si faltan palabras. Menos piezas, más rápido y mejor escrito.
 *
 * Las herramientas viven ahora donde sí hacen falta: buscar un pictograma entre miles es información
 * que el modelo no tiene y no puede inventarse.
 */
class CuentistaService(
  private val motor: MotorMasi,
  private val repaso: RepasoService,
) {

  /**
   * Genera un cuento. Dos pasadas del modelo como mucho, así que puede acercarse al minuto.
   *
   * @param alAvanzar por qué paso va.
   * @param alEscribir el texto según llega, para que la espera no sea una pantalla muerta.
   */
  suspend fun escribir(
    alAvanzar: (PasoCuento) -> Unit = {},
    alEscribir: (String) -> Unit = {},
  ): ResultadoCuento {
    alAvanzar(PasoCuento.ELIGIENDO_PALABRAS)
    val disponibles = repaso.palabrasParaCuento(MAX_PALABRAS)
    if (disponibles.size < MIN_PALABRAS) return ResultadoCuento.SinPalabras

    // Tres, no cinco: con más, el cuento deja de ser un cuento y se vuelve una lista de palabras.
    val objetivo = disponibles.take(CUANTAS_POR_CUENTO)
    Log.i(TAG, "Escribiendo con: ${objetivo.joinToString(", ")}")

    alAvanzar(PasoCuento.ESCRIBIENDO)
    val respuesta =
      generar(Prompts.turnoCuentista(objetivo), alEscribir) ?: return ResultadoCuento.NoSePudo

    // El título viene en la primera línea de la MISMA respuesta. Ver FiltroDeCuento.partir.
    var crudo = FiltroDeCuento.partir(respuesta)
    var cobertura = ComprobadorDePalabras.comprobar(crudo.texto, objetivo)
    Log.i(TAG, "Primer intento: ${cobertura.incluidas.size}/${objetivo.size} palabras, título='${crudo.titulo}'")

    // El reintento SOLO si no entró ni una palabra.
    //
    // Antes se reintentaba por debajo del 60 %, y medido en el teléfono no servía: 1/3 → 1/3, con
    // dieciséis segundos tirados y el texto reescribiéndose a la vista del niño. La causa no era el
    // cuento sino las palabras — "pésame" no entra en una historia infantil por mucho que insistas.
    // Con una sola palabra dentro el cuento ya cumple; con cero, no cumple nada.
    if (cobertura.incluidas.isEmpty()) {
      alAvanzar(PasoCuento.REVISANDO)
      // Sin `alEscribir`: el primer cuento se queda en pantalla mientras se rehace por detrás. Verlo
      // borrarse y reescribirse era desconcertante y no aportaba nada.
      val segundo = generar(Prompts.turnoCuentistaReintento(crudo.texto, cobertura.faltan)) {}
      if (segundo != null) {
        val otro = FiltroDeCuento.partir(segundo)
        val nueva = ComprobadorDePalabras.comprobar(otro.texto, objetivo)
        Log.i(TAG, "Reintento: ${nueva.incluidas.size}/${objetivo.size} palabras")
        if (nueva.incluidas.size > cobertura.incluidas.size) {
          crudo = otro
          cobertura = nueva
        }
      }
    }

    alAvanzar(PasoCuento.GUARDANDO)
    val titulo = sinRepetir(crudo.titulo, crudo.texto)
    val revision =
      FiltroDeCuento.revisar(titulo = titulo, texto = crudo.texto, objetivo = objetivo)
    return when (revision) {
      is RevisionCuento.Rechazado -> {
        Log.w(TAG, "Cuento rechazado: ${revision.motivo}")
        ResultadoCuento.NoSirve(revision.motivo)
      }

      is RevisionCuento.Aceptado -> {
        val cuento =
          repaso.guardarCuento(
            titulo = revision.titulo,
            texto = revision.texto,
            palabras = cobertura.incluidas,
          )
        // El cuento entra en la pantalla de Escuchar como cualquier página fotografiada. Esta línea
        // es toda la integración: la costura estaba puesta desde que existe VersionTexto.CURADA.
        ResultadoCuento.Exito(cuento, Lectura.de(revision.texto, VersionTexto.CURADA))
      }
    }
  }

  /**
   * Se asegura de que el título no choque con uno que ya existe.
   *
   * **Comprobar es trabajo de SQL** —exacto, instantáneo— y escribir uno nuevo es trabajo del
   * modelo. Es el mismo reparto que rige la búsqueda de pictogramas: al modelo se le llama solo
   * cuando el código ya no puede hacer nada.
   *
   * Y solo se le llama en ese caso. Como el título ya viene dentro de la respuesta del cuento, esta
   * pasada extra es la excepción, no la norma: con los arranques narrativos aleatorios, dos cuentos
   * seguidos rara vez coinciden de nombre.
   */
  private suspend fun sinRepetir(titulo: String, cuento: String): String {
    if (titulo.isBlank()) return ""
    if (!repaso.tituloRepetido(titulo)) return titulo

    Log.i(TAG, "Título repetido ('$titulo'); pidiendo otro")
    val usados = repaso.titulosDeCuentos()
    val otro = generar(Prompts.turnoTitulo(cuento, usados)) {}?.let(::limpiarTitulo)
    return if (otro != null && !repaso.tituloRepetido(otro)) otro else ""
  }

  /**
   * Deja un título suelto en algo presentable.
   *
   * **No recorta.** El recorte a cinco palabras produjo "La pelota perdida en el" y "Mateo y el sol
   * de": el modelo escribía bien y el corte lo destrozaba. Si se pasa de largo se descarta entero y
   * decide el respaldo, que al menos da algo con sentido.
   */
  private fun limpiarTitulo(crudo: String): String? {
    val limpio =
      crudo
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
        .trim()
        .trim('"', '«', '»', '*', '#', '.', ':', '-', ' ')
        .replace(Regex("""^(título|titulo)\s*:?\s*""", RegexOption.IGNORE_CASE), "")
        .trim()
    val palabras = limpio.split(Regex("""\s+""")).filter { it.isNotBlank() }
    return if (limpio.length >= 3 && palabras.size <= FiltroDeCuento.MAX_PALABRAS_TITULO) limpio
    else null
  }

  /** Una pasada del modelo. Devuelve null si se agotó el tiempo sin escribir nada. */
  private suspend fun generar(turno: String, alEscribir: (String) -> Unit): String? {
    val acumulado = StringBuilder()
    withTimeoutOrNull(TIMEOUT_MS) {
      motor.generar(rol = Rol.CUENTISTA, texto = turno).collect {
        acumulado.append(it)
        alEscribir(acumulado.toString())
      }
    }
    return acumulado.toString().trim().ifBlank { null }
  }

  private companion object {
    /** Con menos de dos palabras guardadas no hay cuento que practicar. */
    const val MIN_PALABRAS = 2

    const val MAX_PALABRAS = 5

    const val CUANTAS_POR_CUENTO = 3

    /** Un cuento de 120 palabras son unos 160 tokens; 90 s dan margen de sobra por pasada. */
    const val TIMEOUT_MS = 90_000L

    /** Cuántas veces se le pide un título si el que dio ya existía. */
    const val INTENTOS_TITULO = 2

    const val MAX_PALABRAS_TITULO = 5
  }
}
