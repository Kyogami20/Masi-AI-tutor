package pe.masi.servicios

/**
 * Trocea un texto en los fragmentos que el niño va a leer de uno en uno.
 *
 * Es la pieza que decide el ritmo de toda la sesión de lectura, y responde a dos exigencias que
 * tiran en direcciones opuestas:
 *
 *  - **Pedagógica**: leer acompañado, oración a oración, es exactamente como se practica la lectura
 *    con un niño con dislexia. Un fragmento tiene que ser una unidad con sentido, no un pedazo
 *    cortado por la mitad.
 *  - **Técnica**: cada fragmento se graba y se manda al modelo. Un clip largo ocupa más tokens,
 *    tarda más y —lo importante— le da al modelo más contexto con el que "arreglar" el error de
 *    pronunciación que justamente queremos detectar.
 *
 * De ahí el tope de palabras. La versión anterior de este trozado vivía en `PaginaLeida.enFrases()`
 * y cortaba con `chunked(8)`: partía a ciegas cada 8 palabras sin mirar la puntuación, así que una
 * oración de 9 palabras se convertía en un fragmento de 8 y otro de 1. Aquí se corta por donde el
 * español permite respirar.
 *
 * **La regla que no se negocia** es la de [fragmentar]: unir los fragmentos devuelve el texto
 * completo. Ni una palabra se queda fuera. Es lo que garantiza que la sesión acompañe al niño hasta
 * el final de la página, y está fijada por test.
 *
 * Kotlin puro, sin nada de Android: se puede probar en la JVM.
 */
object Segmentador {

  /**
   * Tope de palabras por fragmento.
   *
   * A 1 palabra por segundo —el ritmo de un niño de 7 años con dificultades— son unos 10 s de
   * audio, que es lo que admite `GrabadorAudio.MAX_SEG_FRASE`.
   */
  const val MAX_PALABRAS = 10

  /** Por debajo de esto un fragmento no es una unidad de lectura, es una esquirla. */
  const val MIN_PALABRAS = 3

  /** El separador que el LECTOR pone entre oraciones. Ver [pe.masi.motor.Prompts.LECTOR]. */
  const val PISTA = '|'

  /**
   * Abreviaturas cuyo punto NO termina una oración.
   *
   * Sin esto, "el Sr. Barbicane" se parte en dos fragmentos y el segundo empieza en mayúscula a
   * media frase. La lista es corta a propósito: cubre lo que aparece en un libro escolar peruano.
   */
  private val ABREVIATURAS =
    setOf(
      "sr", "sra", "srta", "dr", "dra", "ud", "uds", "ee", "uu", "etc", "pag", "pág", "núm", "num",
      "av", "ing", "lic", "prof", "art", "cap",
    )

  /**
   * Palabras por las que se puede partir una oración larga sin romperla del todo.
   *
   * Van después de las comas en la escala de preferencia: cortar antes de un "porque" deja dos
   * mitades que se sostienen solas.
   */
  private val CONJUNCIONES =
    setOf(
      "y", "e", "o", "u", "pero", "porque", "que", "cuando", "donde", "si", "aunque", "mientras",
      "para", "como", "pues", "sino", "mas",
    )

  /**
   * Palabras con las que un fragmento no puede terminar.
   *
   * Son artículos, preposiciones y conjunciones: piezas que anuncian lo que viene después. Un
   * fragmento que acaba en "de los" deja al niño colgado a media construcción, y al modelo le da un
   * final de frase que no existe.
   */
  private val NO_CIERRAN =
    setOf(
      "el", "la", "los", "las", "un", "una", "unos", "unas", "lo", "al", "del",
      "a", "ante", "bajo", "con", "contra", "de", "desde", "en", "entre", "hacia", "hasta",
      "para", "por", "según", "sin", "sobre", "tras",
      "y", "e", "o", "u", "que", "ni", "pero", "porque", "como", "cuando", "donde", "si",
      "su", "sus", "mi", "mis", "tu", "tus", "se", "me", "te", "le", "les", "nos", "muy", "más",
      "no", "ya", "también", "solo", "sólo",
      // Auxiliares: piden un participio o un infinitivo detrás. "sin haber | pasado" parte justo
      // por donde no se puede.
      "haber", "ha", "han", "había", "habían", "hemos", "he", "has",
      "ser", "es", "son", "era", "eran", "fue", "fueron",
      "estar", "está", "están", "estaba", "estaban",
      "puede", "pueden", "podía", "debe", "deben", "va", "van", "iba", "iban", "hay",
    )

  /**
   * El texto tal cual se le enseña al niño: sin las marcas de la pista y con los espacios
   * normalizados.
   *
   * El `|` es un detalle de cómo hablamos con el modelo. Nunca puede llegar a la pantalla.
   */
  fun limpiar(texto: String): String =
    texto.replace(PISTA, ' ').replace(Regex("""\s+"""), " ").trim()

  /**
   * Trocea el texto. **La unión de lo que devuelve es el texto entero**, sin pérdidas.
   *
   * @param texto puede venir con marcas [PISTA]; se usan como sugerencia de corte y se eliminan.
   */
  fun fragmentar(texto: String): List<String> {
    val limpio = limpiar(texto)
    if (limpio.isEmpty()) return emptyList()

    val porOraciones = cortarEnOraciones(texto)
    val dentroDelTope = porOraciones.flatMap { partirSiEsLarga(it) }
    return fusionarHuerfanos(dentroDelTope)
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Primer corte: por la pista del modelo y por puntuación fuerte.
   *
   * La puntuación se queda **con el fragmento que termina**, que es como se lee: el signo forma
   * parte de la frase que cierra.
   */
  private fun cortarEnOraciones(texto: String): List<String> {
    val trozos = mutableListOf<String>()
    val actual = StringBuilder()

    texto.forEachIndexed { i, c ->
      if (c == PISTA) {
        // La pista del modelo: corta y no deja rastro.
        volcar(actual, trozos)
        return@forEachIndexed
      }
      actual.append(c)
      if (c in ".!?…:" && terminaOracion(texto, i)) volcar(actual, trozos)
    }
    volcar(actual, trozos)
    return trozos
  }

  private fun volcar(buffer: StringBuilder, destino: MutableList<String>) {
    val trozo = buffer.toString().replace(Regex("""\s+"""), " ").trim()
    if (trozo.isNotEmpty()) destino.add(trozo)
    buffer.setLength(0)
  }

  /**
   * ¿Ese signo cierra de verdad una oración?
   *
   * Tres casos en los que no: un decimal ("3.14"), una abreviatura ("Sr."), y un signo que no va
   * seguido de espacio (dentro de una palabra, como en una URL o "EE.UU.").
   */
  private fun terminaOracion(texto: String, posicion: Int): Boolean {
    val c = texto[posicion]
    val siguiente = texto.getOrNull(posicion + 1)

    // Sin espacio detrás no hay corte. Cubre "3.14" y el punto interno de "EE.UU.".
    if (siguiente != null && !siguiente.isWhitespace() && siguiente != PISTA) return false

    if (c == '.') {
      val anterior = texto.substring(0, posicion).takeLastWhile { it.isLetter() }
      // "Sr." y compañía. También una inicial suelta: "J. Verne".
      if (anterior.length == 1 && anterior[0].isUpperCase()) return false
      if (anterior.lowercase() in ABREVIATURAS) return false
    }
    return true
  }

  /**
   * Segundo corte: las oraciones que se pasan del tope se parten por su mejor costura interna.
   *
   * Se busca el candidato **más cercano al centro** para que las dos mitades queden equilibradas;
   * cortar por la primera coma dejaría un fragmento de 2 palabras y otro de 12, y habría que volver
   * a partir el segundo.
   */
  private fun partirSiEsLarga(oracion: String): List<String> {
    val palabras = oracion.split(' ').filter { it.isNotBlank() }
    if (palabras.size <= MAX_PALABRAS) return listOf(oracion)

    val corte = mejorCorte(palabras) ?: (palabras.size / 2)
    val izquierda = palabras.subList(0, corte).joinToString(" ")
    val derecha = palabras.subList(corte, palabras.size).joinToString(" ")
    // Recursivo: cada mitad puede seguir siendo demasiado larga.
    return partirSiEsLarga(izquierda) + partirSiEsLarga(derecha)
  }

  /**
   * El índice de palabra por donde partir, en orden de preferencia: punto y coma, coma,
   * conjunción. Devuelve null si no hay ninguna costura y hay que partir por el centro.
   */
  private fun mejorCorte(palabras: List<String>): Int? {
    val centro = palabras.size / 2.0

    // Un corte tras la palabra i produce fragmentos de tamaño i y size-i. Se descartan los que
    // dejarían una esquirla en cualquiera de los dos lados.
    fun valido(i: Int) = i >= MIN_PALABRAS && palabras.size - i >= MIN_PALABRAS

    fun masCentrado(candidatos: List<Int>): Int? =
      candidatos.filter { valido(it) }.minByOrNull { kotlin.math.abs(it - centro) }

    val trasPuntoYComa = palabras.indices.filter { palabras[it].endsWith(';') }.map { it + 1 }
    masCentrado(trasPuntoYComa)?.let { return it }

    val trasComa = palabras.indices.filter { palabras[it].endsWith(',') }.map { it + 1 }
    masCentrado(trasComa)?.let { return it }

    // Antes de la conjunción, no después: "y" abre lo que viene.
    val anteConjuncion =
      palabras.indices.filter { normalizar(palabras[it]) in CONJUNCIONES && it > 0 }
    masCentrado(anteConjuncion)?.let { return it }

    // Sin ninguna costura de puntuación: se parte por el espacio menos malo. Sin este último
    // filtro, una oración larga sin comas se partía justo por el centro y el resultado real fue
    // "…de los Estados" / "Unidos se estableció…": un nombre propio roto en dos.
    val aceptables = palabras.indices.filter { cortePermitido(palabras, it) }.map { it + 1 }
    masCentrado(aceptables)?.let { return it }

    return null
  }

  /**
   * ¿Se puede partir dejando `palabras[indice]` como última palabra del fragmento izquierdo?
   *
   * Dos vetos: no terminar en una palabra que anuncia lo que viene ([NO_CIERRAN]), y no separar dos
   * palabras con mayúscula seguidas, que casi siempre son un nombre propio compuesto ("Estados
   * Unidos", "West Point", "Nueva York"). La mayúscula de la primera palabra de la oración no
   * cuenta: esa es de la oración, no del nombre.
   */
  private fun cortePermitido(palabras: List<String>, indice: Int): Boolean {
    if (normalizar(palabras[indice]) in NO_CIERRAN) return false
    val siguiente = palabras.getOrNull(indice + 1) ?: return true
    val propioIzquierda = indice > 0 && palabras[indice].firstOrNull()?.isUpperCase() == true
    val propioDerecha = siguiente.firstOrNull()?.isUpperCase() == true
    return !(propioIzquierda && propioDerecha)
  }

  private fun normalizar(palabra: String) =
    palabra.trim(',', ';', '.', ':', '!', '?', '"', '«', '»').lowercase()

  /**
   * Tercer paso: las esquirlas se pegan al vecino.
   *
   * Un fragmento de una palabra es casi siempre el rabo de una oración que se pasó del tope por
   * poco. Se prefiere pegarlo al **anterior**, que es de donde salió.
   *
   * Si la fusión se pasara del tope, la esquirla se queda como está: un fragmento corto es un mal
   * menor comparado con uno que no cabe en la grabación.
   */
  private fun fusionarHuerfanos(fragmentos: List<String>): List<String> {
    if (fragmentos.size <= 1) return fragmentos
    val salida = mutableListOf<String>()

    for (fragmento in fragmentos) {
      val anterior = salida.lastOrNull()
      if (anterior != null && cuentaPalabras(fragmento) < MIN_PALABRAS) {
        val junto = "$anterior $fragmento"
        if (cuentaPalabras(junto) <= MAX_PALABRAS) {
          salida[salida.size - 1] = junto
          continue
        }
      }
      salida.add(fragmento)
    }

    // El primero no tiene anterior con el que fusionarse; se le pega el segundo si cabe.
    if (salida.size >= 2 && cuentaPalabras(salida[0]) < MIN_PALABRAS) {
      val junto = "${salida[0]} ${salida[1]}"
      if (cuentaPalabras(junto) <= MAX_PALABRAS) {
        salida[1] = junto
        salida.removeAt(0)
      }
    }
    return salida
  }

  fun cuentaPalabras(texto: String): Int = texto.split(Regex("""\s+""")).count { it.isNotBlank() }
}
