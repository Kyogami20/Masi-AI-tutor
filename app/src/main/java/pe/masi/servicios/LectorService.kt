package pe.masi.servicios

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import pe.masi.motor.MotorMasi
import pe.masi.motor.ParserTolerante
import pe.masi.motor.Prompts
import pe.masi.motor.Rol

private const val TAG = "MasiLector"

/**
 * Tope de espera del LECTOR.
 *
 * La visión es lo más lento de todo con diferencia: hay que cargar el encoder bajo demanda y
 * procesar la imagen, y en un teléfono de gama media el primer token tarda hasta 20 s. Después hay
 * que generar la transcripción entera a ~9 tokens/s, así que una página de 300 tokens son otros
 * 35 s largos. 150 s es generoso a propósito: agotar el tiempo aquí se ve en pantalla como "no
 * entendí la foto", y eso desanima al niño mucho más que esperar.
 */
private const val TIMEOUT_MS = 150_000L

/**
 * Lo que devuelve el modelo, y nada más: una cadena de texto.
 *
 * Es un DTO de transporte, no el modelo de dominio. Todo lo que la app *garantiza* sobre un texto
 * —cómo se trocea, cómo se silabea— se calcula en [Lectura], que es con quien habla el resto de la
 * aplicación.
 */
@Serializable data class PaginaLeida(val texto: String = "")

sealed interface ResultadoLector {
  data class Exito(val lectura: Lectura) : ResultadoLector

  /** La foto salió borrosa o no había texto. Se le pide al niño que la repita, sin dramas. */
  data object NoSeEntiende : ResultadoLector

  data class Fallo(val mensaje: String) : ResultadoLector
}

/**
 * Agente LECTOR: foto de una página → texto accesible.
 *
 * Es la puerta de entrada de Masi. Sin esto no hay contenido que leer, así que aquí se es
 * generoso con los reintentos: el niño ya hizo el esfuerzo de tomar la foto.
 */
class LectorService(private val motor: MotorMasi) {

  /**
   * @param onProgreso recibe cuántas palabras lleva transcritas el modelo, según van llegando.
   *   Transcribir una página entera son 40 segundos largos de generación token a token, y sin una
   *   señal de vida la pantalla parece colgada.
   */
  suspend fun leerPagina(fotoPng: ByteArray, onProgreso: (Int) -> Unit = {}): ResultadoLector {
    val primera = intentar(fotoPng, reintento = false, onProgreso = onProgreso)
    if (primera != null) return terminar(primera)

    // El modelo devolvió algo que no era JSON. Se le pide otra vez, más explícito.
    Log.w(TAG, "Primer intento sin JSON válido; reintentando")
    val segunda = intentar(fotoPng, reintento = true, onProgreso = onProgreso)
    if (segunda != null) return terminar(segunda)

    return ResultadoLector.Fallo("No pude leer esta página")
  }

  private suspend fun intentar(
    fotoPng: ByteArray,
    reintento: Boolean,
    onProgreso: (Int) -> Unit,
  ): PaginaLeida? {
    val instruccion =
      if (reintento) {
        Prompts.reintentoJson("""{"texto": "..."}""")
      } else {
        "Lee esta página."
      }

    val bruto =
      withTimeoutOrNull(TIMEOUT_MS) {
        val acumulado = StringBuilder()
        var ultimoAviso = 0
        motor.generar(rol = Rol.LECTOR, texto = instruccion, imagen = fotoPng).collect { trozo ->
          acumulado.append(trozo)
          // Contar espacios es una aproximación burda al número de palabras, pero es O(1) por
          // trozo y lo único que importa es que la cifra suba: es una señal de vida, no una
          // métrica.
          val palabras = acumulado.count { it == ' ' }
          if (palabras > ultimoAviso) {
            ultimoAviso = palabras
            onProgreso(palabras)
          }
        }
        acumulado.toString()
      }

    if (bruto == null) {
      Log.w(TAG, "El LECTOR agotó el tiempo de espera (${TIMEOUT_MS / 1000} s)")
      return null
    }
    // Este log es la única forma de saber, desde el teléfono, si el problema fue el modelo o el
    // parser. Sin él, cualquier fallo de esta pantalla es indistinguible de cualquier otro.
    Log.d(TAG, "LECTOR respondió (${bruto.length} car.): ${bruto.take(500)}")
    return ParserTolerante.parsear<PaginaLeida>(bruto)
  }

  private fun terminar(pagina: PaginaLeida): ResultadoLector {
    if (pagina.texto.isBlank()) return ResultadoLector.NoSeEntiende

    // Demasiados [?] significa que la foto no se lee: mejor repetirla que enseñar basura.
    val ilegibles = Regex("""\[\?]""").findAll(pagina.texto).count()
    val palabras = pagina.texto.split(Regex("""\s+""")).size
    if (palabras > 0 && ilegibles > palabras / 3) return ResultadoLector.NoSeEntiende

    // Aquí se cruza la frontera: lo que llegó es una cadena del modelo, lo que sale es una lectura
    // con garantías (fragmentos que cubren el texto entero, sílabas exactas).
    val lectura = Lectura.de(pagina.texto)
    if (lectura.estaVacia) return ResultadoLector.NoSeEntiende
    Log.d(TAG, "Página troceada en ${lectura.fragmentos.size} fragmentos")
    return ResultadoLector.Exito(lectura)
  }
}
