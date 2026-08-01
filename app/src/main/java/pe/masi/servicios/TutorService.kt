package pe.masi.servicios

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import pe.masi.motor.MotorMasi
import pe.masi.motor.Prompts
import pe.masi.motor.Rol

private const val TAG = "MasiTutor"

/**
 * Tope de espera del TUTOR.
 *
 * A ~9 tokens por segundo, dos frases cortas salen en unos 5 s. Los 25 s son colchón; pasados
 * esos, se usa una pista de repuesto y el niño no se entera de nada.
 */
private const val TIMEOUT_MS = 25_000L

data class Pista(val texto: String, val deRepuesto: Boolean)

/**
 * Agente TUTOR: un error detectado → una pista amable.
 *
 * Es lo que convierte la detección en pedagogía. Dos cosas gobiernan este archivo:
 *
 * **Nunca se ridiculiza el error.** La respuesta empieza siempre reconociendo el intento. Nada de
 * "mal", "incorrecto" ni "fallaste".
 *
 * **Máximo dos frases**, y eso dejó de ser solo pedagógico: a ~9 tokens/s, cuatro frases son doce
 * segundos de espera. Es una restricción de ingeniería tanto como de diseño.
 */
class TutorService(private val motor: MotorMasi) : Explicador {

  /**
   * Pide una pista para el error. Nunca lanza ni devuelve error: si el modelo no responde a
   * tiempo, o responde algo que no sirve, entrega una pista de repuesto escrita a mano.
   */
  override suspend fun explicar(error: ErrorLectura): Pista {
    val silabas = Silabas.separar(error.esperado)

    val respuesta =
      withTimeoutOrNull(TIMEOUT_MS) {
        motor.generarCompleto(
          rol = Rol.TUTOR,
          texto =
            Prompts.turnoTutor(esperado = error.esperado, dicho = error.dicho, silabas = silabas),
        )
      }

    val limpia = respuesta?.let { limpiar(it) }
    if (limpia == null || !FiltroDePistas.esUtil(limpia, error.esperado, silabas)) {
      Log.w(TAG, "Pista descartada: '${limpia ?: "(sin respuesta)"}'. Se usa la de repuesto.")
      return Pista(BancoDePistas.para(error, silabas), deRepuesto = true)
    }
    return Pista(limpia, deRepuesto = false)
  }

  /**
   * Recorta a dos frases y quita adornos.
   *
   * Los modelos pequeños a veces se arrancan con comillas, viñetas o una tercera frase. Cortar
   * aquí es más barato y más fiable que volver a pedírselo.
   */
  private fun limpiar(bruto: String): String {
    var t = bruto.trim().removeSurrounding("\"").trim()
    t = t.removePrefix("- ").removePrefix("* ").trim()
    val frases =
      Regex("""[^.!?¡¿]+[.!?]*""").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }
    return frases.take(2).joinToString(" ").trim()
  }

}

/**
 * Decide si una pista del modelo sirve o hay que tirar de repuesto.
 *
 * Va aparte del servicio, sin dependencias de Android ni del motor, porque es una regla de producto
 * con consecuencias directas sobre lo que oye un niño: merece tests propios.
 *
 * En pruebas reales el modelo produjo "¡Buen try! Mira el pan" para la palabra "establecio", y
 * "Qué bien lo hiciste! Mira el pan" para "contribuye": mezcla de idiomas y sin ninguna relación
 * con la palabra. Son fallos típicos de un modelo pequeño y multilingüe al que se le pidió "usa
 * objetos de la vida diaria peruana" y se lo tomó como "nombra estas cosas".
 *
 * La comprobación decisiva es la segunda: **la pista tiene que hablar de la palabra.** Con esa sola
 * regla se cazan todos los casos observados.
 */
object FiltroDePistas {

  /**
   * Palabras inglesas que delatan mezcla de idiomas.
   *
   * Cuidado al ampliar esta lista: aquí solo pueden entrar palabras que **no existen en español**.
   * Una versión anterior incluía "no", que es español corriente, y con eso el filtro habría
   * rechazado casi cualquier pista válida y el banco de repuesto habría saltado siempre.
   */
  val INGLES =
    setOf(
      "try", "good", "nice", "great", "well", "done", "look", "the", "you", "your", "word",
      "read", "letter", "sound", "again", "keep", "going", "almost", "close", "very", "job",
      "awesome", "and", "with", "this", "that", "is", "it", "what", "how", "now", "please",
    )

  /** Lenguaje de déficit. Un niño que se cree tonto deja de intentarlo, y eso dura años. */
  val PROHIBIDAS = setOf("mal", "error", "incorrecto", "fallaste", "fallo", "equivocado", "malo")

  fun esUtil(pista: String, palabra: String, silabas: String): Boolean {
    if (pista.length < 15 || pista.length > 220) return false

    val palabrasDeLaPista = DetectorErrores.palabras(pista).toSet()
    if (palabrasDeLaPista.any { it in INGLES }) return false
    if (palabrasDeLaPista.any { it in PROHIBIDAS }) return false

    // ¿Habla de la palabra? Vale la palabra entera, la silabeada, o dos de sus sílabas.
    val objetivo = DetectorErrores.normalizar(palabra)
    val normalizada = DetectorErrores.normalizar(pista)
    val silabasSueltas = silabas.split("-").filter { it.length >= 2 }
    return normalizada.contains(objetivo) ||
      normalizada.contains(DetectorErrores.normalizar(silabas)) ||
      silabasSueltas.count { normalizada.contains(DetectorErrores.normalizar(it)) } >= 2
  }
}

/**
 * Pistas escritas a mano, por tipo de error.
 *
 * Son la red de seguridad, y en la práctica saltan a menudo: un modelo de 2B efectivos no acierta
 * siempre con una pista pedagógica útil. Si tarda demasiado, falla, o dice algo que no viene a
 * cuento, el niño recibe igualmente algo concreto y amable. Nunca un error crudo, nunca un silencio.
 */
object BancoDePistas {

  fun para(error: ErrorLectura, silabas: String): String {
    val primeraSilaba = silabas.substringBefore("-").ifBlank { error.esperado }
    val cuantas = silabas.split("-").size
    return when (error.tipo) {
      TipoError.SUSTITUCION_INICIAL ->
        "¡Muy bien por intentarlo! Mira cómo empieza: $primeraSilaba. Dice $silabas."
      TipoError.INVERSION ->
        "¡Casi! Están todas las letras, pero cambiaron de sitio. Míralas otra vez: $silabas."
      TipoError.OMISION -> "Esta palabra se nos escapó. Vamos juntos, despacito: $silabas."
      TipoError.OMISION_PARCIAL ->
        "¡Buen intento! Se quedó un sonidito adentro. Tiene $cuantas partes: $silabas."
      TipoError.SUSTITUCION ->
        "¡Bien! Vamos a decirla otra vez, parte por parte: $silabas."
    }
  }
}
