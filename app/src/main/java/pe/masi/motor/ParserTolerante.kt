package pe.masi.motor

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Red de seguridad para el JSON del modelo.
 *
 * Los modelos pequeños casi siempre devuelven el JSON pedido, pero a veces lo envuelven en un
 * bloque de código markdown, le ponen una frase de cortesía delante, o cierran mal las comillas.
 * Esta clase asume lo peor y rescata lo que puede: extrae el primer objeto JSON equilibrado y lo
 * parsea en modo permisivo.
 *
 * Es la tercera línea de defensa del documento maestro (§9.5), la única que se implementa siempre.
 * Las otras dos —function calling nativo y decodificación restringida— son mejores pero opcionales;
 * esta no puede faltar porque es la que evita que un JSON torcido llegue a la pantalla de un niño.
 */
object ParserTolerante {

  const val TAG = "MasiParser"

  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  /**
   * Devuelve el objeto parseado, o `null` si no hubo forma.
   *
   * `null` no es un error que mostrar: quien llama debe reintentar o degradar con elegancia. Nunca
   * un error crudo en pantalla.
   */
  inline fun <reified T> parsear(bruto: String): T? {
    val recortado = recortar(bruto) ?: return null
    return try {
      json.decodeFromString<T>(recortado)
    } catch (e: Exception) {
      Log.w(TAG, "JSON no parseable tras recortar: ${recortado.take(200)}", e)
      null
    }
  }

  /**
   * Se queda con el primer objeto JSON que encuentre: del primer `{` a su `}` equilibrado.
   *
   * Contar llaves (en vez de buscar el último `}`) importa porque el modelo a veces añade una
   * segunda respuesta después de la primera, y quedarse con las dos rompería el parseo.
   */
  fun recortar(bruto: String): String? {
    val sinCercas = bruto.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()
    val inicio = sinCercas.indexOf('{')
    if (inicio < 0) return null

    var profundidad = 0
    var dentroDeCadena = false
    var escapado = false
    for (i in inicio until sinCercas.length) {
      val c = sinCercas[i]
      when {
        escapado -> escapado = false
        dentroDeCadena && c == '\\' -> escapado = true
        c == '"' -> dentroDeCadena = !dentroDeCadena
        dentroDeCadena -> {}
        c == '{' -> profundidad++
        c == '}' -> {
          profundidad--
          if (profundidad == 0) return sinCercas.substring(inicio, i + 1)
        }
      }
    }
    // Objeto sin cerrar: el modelo se quedó a medias o lo cortó el tope de tokens.
    return null
  }
}
