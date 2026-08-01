package pe.masi.servicios

/** Por qué no se le puede enseñar este cuento a un niño. */
enum class MotivoRechazo {
  DEMASIADO_CORTO,
  DEMASIADO_LARGO,
  SIN_NINGUNA_PALABRA,
  NO_ES_UN_CUENTO,
}

/** La respuesta del modelo, ya separada en sus dos partes. */
data class CuentoCrudo(val titulo: String, val texto: String)

sealed interface RevisionCuento {
  data class Aceptado(val titulo: String, val texto: String) : RevisionCuento

  data class Rechazado(val motivo: MotivoRechazo) : RevisionCuento
}

/**
 * El último filtro antes de que un texto generado por el modelo llegue a los ojos de un niño.
 *
 * Sigue el precedente de [FiltroDePistas]: un modelo de 2B acierta casi siempre y falla de formas
 * raras, así que entre el modelo y el niño hay siempre una comprobación determinista. **Ningún
 * cuento se muestra ni se guarda sin pasar por aquí.**
 *
 * No pretende ser un moderador de contenido —eso no se resuelve con reglas, y el prompt del
 * CUENTISTA ya acota el terreno—, sino atrapar los fallos de forma que sí se pueden detectar: que el
 * modelo devuelva la plantilla en vez del cuento, que se quede a medias, o que ignore por completo
 * las palabras que eran el motivo de generarlo.
 */
object FiltroDeCuento {

  /** Por debajo de esto no es un cuento, es una frase suelta. */
  const val MIN_PALABRAS = 25

  /**
   * Por encima de esto es demasiado para una sesión.
   *
   * A ~1 palabra por segundo que lee un niño de 7 años con dificultades, 160 palabras son casi tres
   * minutos de lectura en voz alta. El tope es generoso respecto a las 80–120 que se le piden al
   * modelo, para no rechazar un cuento bueno que se pasó un poco.
   */
  const val MAX_PALABRAS = 160

  /**
   * Tope de palabras de un título, generoso a propósito.
   *
   * El límite anterior era cinco y **se aplicaba recortando**, que es lo peor que se puede hacer:
   * "La pelota perdida en el jardín" salía como "La pelota perdida en el". Ahora se pide de tres a
   * cinco en el prompt, y aquí solo se rechaza lo que claramente no es un título.
   */
  const val MAX_PALABRAS_TITULO = 10

  /**
   * Señales de que el modelo devolvió instrucciones, o habló de sí mismo, en vez de narrar.
   *
   * Pasa cuando el bucle de herramientas se confunde y el modelo "piensa en voz alta". Un niño no
   * debe leer eso.
   */
  private val DELATORES =
    listOf(
      "como modelo",
      "como asistente",
      "no puedo",
      "lo siento",
      "aquí tienes",
      "aquí está el cuento",
      "el cuento que me pediste",
      "instrucciones:",
      "herramienta",
      "función",
      "json",
      "```",
      // Andamiaje del propio function calling. Llegó a la pantalla de un niño de verdad: el modelo
      // emitió una llamada MAL FORMADA —`save_Story` con mayúscula, `story_` con guion bajo— que el
      // runtime no reconoció como tal, así que salió por el flujo de texto y este filtro la dejó
      // pasar entera, delimitadores incluidos.
      "tool_call",
      "<|",
      "|>",
    )

  private val NEGRITA = Regex("""\*{1,3}([^*\n]+)\*{1,3}""")
  private val SUBRAYADO = Regex("""_{1,2}([^_\n]+)_{1,2}""")
  private val ENCABEZADO = Regex("""^#{1,6}\s*""", RegexOption.MULTILINE)
  private val ESPACIOS = Regex("""[ \t]+""")

  /**
   * Quita el Markdown que el modelo mete por su cuenta.
   *
   * Aparece sin que nadie lo pida: subraya con `**` las palabras que le encargaste practicar, que es
   * justo lo que un modelo de chat aprendió a hacer. Pero en Masi no hay quien interprete Markdown,
   * así que el niño ve literalmente `**mamá**`, y los asteriscos le estorban para leer — que es
   * exactamente lo contrario de lo que hace esta pantalla.
   *
   * Se limpia aquí y no al pintarlo, porque lo que se guarda en la biblioteca tiene que estar ya
   * limpio: el cuento se relee muchas veces y también se lee en voz alta.
   */
  fun limpiarMarcas(texto: String): String =
    texto
      .replace(NEGRITA, "$1")
      .replace(SUBRAYADO, "$1")
      .replace(ENCABEZADO, "")
      .replace("`", "")
      .replace(ESPACIOS, " ")
      .trim()

  /**
   * Separa el título del cuento en la respuesta del modelo.
   *
   * El título viene **en la misma generación**, en la primera línea. Antes se pedía en una llamada
   * aparte y costaba una pasada entera del modelo —unos seis segundos de las casi sesenta que
   * tardaba todo— para algo que el modelo puede escribir a la vez y con el cuento entero delante.
   *
   * Se acepta la primera línea como título solo si **parece** un título. Tres señales, y las tres
   * tienen que cumplirse:
   *  - tiene entre 2 y 10 palabras;
   *  - no termina en punto, que es lo que haría la primera frase de un cuento;
   *  - lo que queda debajo sigue siendo lo bastante largo para ser el cuento.
   *
   * Si alguna falla, se da por hecho que el modelo ignoró el formato: todo pasa a ser cuento y el
   * título lo pone el respaldo. **Nunca se recorta un título a la fuerza.** Recortarlo a cinco
   * palabras produjo "La pelota perdida en el" y "Mateo y el sol de" — el modelo escribía bien y el
   * corte lo destrozaba.
   */
  fun partir(respuesta: String): CuentoCrudo {
    val limpio = limpiarMarcas(respuesta)
    val lineas = limpio.lines()
    val primera = lineas.firstOrNull()?.trim().orEmpty()
    val resto = lineas.drop(1).joinToString(" ").trim()

    val palabrasTitulo = primera.split(Regex("""\s+""")).count { it.isNotBlank() }
    val palabrasResto = resto.split(Regex("""\s+""")).count { it.isNotBlank() }

    val pareceTitulo =
      palabrasTitulo in 2..MAX_PALABRAS_TITULO &&
        !primera.endsWith(".") &&
        palabrasResto >= MIN_PALABRAS

    return if (pareceTitulo) CuentoCrudo(primera, resto) else CuentoCrudo("", limpio)
  }

  fun revisar(titulo: String, texto: String, objetivo: List<String>): RevisionCuento {
    val limpio = limpiarMarcas(texto)
    val cuantas = limpio.split(Regex("""\s+""")).count { it.isNotBlank() }

    if (cuantas < MIN_PALABRAS) return RevisionCuento.Rechazado(MotivoRechazo.DEMASIADO_CORTO)
    if (cuantas > MAX_PALABRAS) return RevisionCuento.Rechazado(MotivoRechazo.DEMASIADO_LARGO)

    val enMinusculas = limpio.lowercase()
    if (DELATORES.any { it in enMinusculas }) {
      return RevisionCuento.Rechazado(MotivoRechazo.NO_ES_UN_CUENTO)
    }

    // Si no aparece NINGUNA de las palabras, el cuento no cumple su única función. Se exige solo
    // una: el umbral de "suficiente" ya lo gestiona [ComprobadorDePalabras] durante la generación,
    // y rechazar aquí un cuento con tres de cinco sería tirar trabajo bueno.
    if (objetivo.isNotEmpty() && ComprobadorDePalabras.comprobar(limpio, objetivo).incluidas.isEmpty()) {
      return RevisionCuento.Rechazado(MotivoRechazo.SIN_NINGUNA_PALABRA)
    }

    return RevisionCuento.Aceptado(titulo = tituloUsable(titulo, limpio), texto = limpio)
  }

  /**
   * Un cuento sin título se quedaría sin nombre en la biblioteca, así que se le pone uno.
   *
   * **Esto es un respaldo y solo eso.** Durante un tiempo funcionó a tiempo completo —el CUENTISTA
   * había dejado de dar títulos y nadie lo notó— y todos los cuentos se llamaban por sus cuatro
   * primeras palabras: "Rosa vivía cerca…". El título de verdad lo pide
   * [FiltroDeCuento.partir] desde la primera línea de la respuesta; aquí solo se cubre que falle.
   *
   * Aun siendo respaldo se intenta que diga algo: se busca el nombre del protagonista.
   *
   * Se elige **la palabra capitalizada que más se repite**, no la primera. La primera casi siempre
   * abre una frase —el primer intento se quedó con "Cada", de "Cada mañana salía…"— mientras que a
   * un protagonista se le nombra varias veces a lo largo del cuento. Es justo lo que lo distingue.
   */
  private fun tituloUsable(titulo: String, texto: String): String {
    val limpio = titulo.trim().trim('"', '«', '»', '*', '#').trim()
    if (limpio.length in 3..60) return limpio

    val protagonista =
      texto
        .split(Regex("""\s+"""))
        .map { it.trim(',', '.', ';', ':', '!', '?', '"') }
        .filter { it.length > 2 && it.first().isUpperCase() }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.takeIf { it.value > 1 }
        ?.key

    return if (protagonista != null) "El cuento de $protagonista" else "Un cuento nuevo"
  }
}
