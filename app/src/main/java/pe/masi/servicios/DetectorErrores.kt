package pe.masi.servicios

/** El tipo de error de lectura, en el vocabulario de la intervención en lectoescritura. */
enum class TipoError {
  /** Cambió el primer sonido: "bedo" por "dedo". El clásico b/d/p. */
  SUSTITUCION_INICIAL,

  /** Las mismas letras en otro orden: "prado" por "pardo". */
  INVERSION,

  /** No leyó la palabra. */
  OMISION,

  /** Se comió parte: "pato" por "plato". */
  OMISION_PARCIAL,

  /** Cualquier otra sustitución. */
  SUSTITUCION,
}

/** Qué pasó con una palabra al alinear lo esperado con lo dicho. */
enum class Operacion {
  IGUAL,
  SUSTITUCION,

  /** Estaba escrita y no se leyó. */
  OMISION,

  /** Se leyó algo que no estaba escrito. */
  INSERCION,
}

/** Una posición de la alineación. `esperado` o `dicho` son nulos en omisión / inserción. */
data class Par(
  val indice: Int,
  val esperado: String?,
  val dicho: String?,
  val operacion: Operacion,
)

/** Una discrepancia concreta entre lo escrito y lo leído. */
data class ErrorLectura(
  /** Índice de la palabra dentro del texto esperado. */
  val indice: Int,
  val esperado: String,
  val dicho: String,
  val tipo: TipoError,
)

/**
 * Compara lo que estaba escrito contra lo que el niño leyó.
 *
 * Todo aquí es código determinista, sin Android y sin modelo, y eso es deliberado: comparar textos
 * no es tarea de un LLM. Un LLM comparando introduce variabilidad justo donde hace falta que no la
 * haya, y encima no se puede depurar. Este archivo, en cambio, se puede probar entero — y se
 * prueba: ver `DetectorErroresTest`.
 *
 * Ojo: lo que sale de aquí son discrepancias EN BRUTO. No se le enseñan al niño tal cual. Antes
 * pasan por [PoliticaConservadora], que es quien decide qué se marca de verdad.
 */
object DetectorErrores {

  private val SIGNOS = Regex("""[^\p{L}\p{N}\s]""")
  private val ESPACIOS = Regex("""\s+""")

  /**
   * Vocales acentuadas del español y su equivalente sin tilde.
   *
   * Se hace con una tabla explícita en vez de con `Normalizer` en forma NFD: la descomposición
   * Unicode partiría la ñ en "n" + tilde combinante, y al quitar los diacríticos "año" se
   * convertiría en "ano". Son palabras distintas y sería un error tonto de cometer.
   */
  private const val CON_TILDE = "áàäâéèëêíìïîóòöôúùûüÁÀÄÂÉÈËÊÍÌÏÎÓÒÖÔÚÙÛÜ"
  private const val SIN_TILDE = "aaaaeeeeiiiioooouuuuAAAAEEEEIIIIOOOOUUUU"

  /**
   * Minúsculas, sin signos, sin tildes, espacios colapsados. **La ñ se conserva.**
   *
   * Quitar tildes hace que "papá" y "papa" se consideren iguales. Es intencional: la transcripción
   * de un modelo no es fiable con los acentos, y marcar un acento como error de lectura sería un
   * falso positivo casi seguro. Conservar la ñ, en cambio, no es negociable: "año" y "ano" son
   * palabras distintas.
   */
  fun normalizar(texto: String): String {
    val sb = StringBuilder(texto.length)
    for (c in texto) {
      val i = CON_TILDE.indexOf(c)
      sb.append(if (i >= 0) SIN_TILDE[i] else c)
    }
    return sb.toString().lowercase().replace(SIGNOS, " ").replace(ESPACIOS, " ").trim()
  }

  /** Trocea en palabras ya normalizadas. */
  fun palabras(texto: String): List<String> {
    val n = normalizar(texto)
    return if (n.isEmpty()) emptyList() else n.split(" ")
  }

  /**
   * Alineación palabra a palabra por distancia de edición, con reconstrucción del camino.
   *
   * Kotlin no trae el equivalente de `difflib`, así que esto va a mano. Es programación dinámica
   * clásica: se llena la matriz de costes y luego se recorre hacia atrás para saber qué operación
   * se usó en cada casilla.
   */
  fun alinear(esperado: List<String>, dicho: List<String>): List<Par> {
    val n = esperado.size
    val m = dicho.size
    val coste = Array(n + 1) { IntArray(m + 1) }
    for (i in 0..n) coste[i][0] = i
    for (j in 0..m) coste[0][j] = j
    for (i in 1..n) {
      for (j in 1..m) {
        val sustitucion = coste[i - 1][j - 1] + if (esperado[i - 1] == dicho[j - 1]) 0 else 1
        val omision = coste[i - 1][j] + 1
        val insercion = coste[i][j - 1] + 1
        coste[i][j] = minOf(sustitucion, omision, insercion)
      }
    }

    val pares = ArrayDeque<Par>()
    var i = n
    var j = m
    while (i > 0 || j > 0) {
      val iguales = i > 0 && j > 0 && esperado[i - 1] == dicho[j - 1]
      val costeSustitucion =
        if (i > 0 && j > 0) coste[i - 1][j - 1] + if (iguales) 0 else 1 else Int.MAX_VALUE
      val costeOmision = if (i > 0) coste[i - 1][j] + 1 else Int.MAX_VALUE
      val costeInsercion = if (j > 0) coste[i][j - 1] + 1 else Int.MAX_VALUE

      when (minOf(costeSustitucion, costeOmision, costeInsercion)) {
        costeSustitucion -> {
          pares.addFirst(
            Par(
              indice = i - 1,
              esperado = esperado[i - 1],
              dicho = dicho[j - 1],
              operacion = if (iguales) Operacion.IGUAL else Operacion.SUSTITUCION,
            )
          )
          i--
          j--
        }
        costeOmision -> {
          pares.addFirst(
            Par(
              indice = i - 1,
              esperado = esperado[i - 1],
              dicho = null,
              operacion = Operacion.OMISION,
            )
          )
          i--
        }
        else -> {
          pares.addFirst(
            Par(indice = i, esperado = null, dicho = dicho[j - 1], operacion = Operacion.INSERCION)
          )
          j--
        }
      }
    }
    return pares.toList()
  }

  /**
   * Qué clase de error es, dada una palabra esperada y lo que sonó.
   *
   * El orden de las comprobaciones importa. La inversión se comprueba antes que la sustitución
   * inicial porque es estrictamente más específica: si las dos palabras tienen exactamente las
   * mismas letras, es una inversión y punto, aunque además cambie la primera. Sin esto, "el" leído
   * como "le" —el ejemplo de manual de inversión— saldría clasificado como sustitución inicial y
   * el TUTOR daría la pista equivocada.
   */
  fun clasificar(esperado: String, dicho: String): TipoError {
    val e = normalizar(esperado)
    val d = normalizar(dicho)
    return when {
      d.isEmpty() -> TipoError.OMISION
      e.length > 1 && e.toList().sorted() == d.toList().sorted() -> TipoError.INVERSION
      e.isNotEmpty() && e.first() != d.first() -> TipoError.SUSTITUCION_INICIAL
      d.length < e.length -> TipoError.OMISION_PARCIAL
      else -> TipoError.SUSTITUCION
    }
  }

  /** Distancia de edición entre dos cadenas, en caracteres. */
  fun distancia(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var anterior = IntArray(b.length + 1) { it }
    var actual = IntArray(b.length + 1)
    for (i in 1..a.length) {
      actual[0] = i
      for (j in 1..b.length) {
        val sustitucion = anterior[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
        actual[j] = minOf(sustitucion, anterior[j] + 1, actual[j - 1] + 1)
      }
      val intercambio = anterior
      anterior = actual
      actual = intercambio
    }
    return anterior[b.length]
  }

  /**
   * Discrepancias en bruto entre el texto escrito y la transcripción de lo leído.
   *
   * Las inserciones (palabras que el niño dijo de más) se ignoran a propósito: casi siempre son
   * ruido del transcriptor, muletillas o el niño repitiéndose, y ninguna de las tres es un error de
   * lectura que merezca corregirse.
   */
  fun comparar(textoEsperado: String, textoDicho: String): List<ErrorLectura> {
    val esperado = palabras(textoEsperado)
    val dicho = palabras(textoDicho)
    if (esperado.isEmpty()) return emptyList()

    return alinear(esperado, dicho).mapNotNull { par ->
      when (par.operacion) {
        Operacion.IGUAL,
        Operacion.INSERCION -> null
        Operacion.OMISION ->
          ErrorLectura(
            indice = par.indice,
            esperado = par.esperado!!,
            dicho = "",
            tipo = TipoError.OMISION,
          )
        Operacion.SUSTITUCION ->
          ErrorLectura(
            indice = par.indice,
            esperado = par.esperado!!,
            dicho = par.dicho!!,
            tipo = clasificar(par.esperado, par.dicho),
          )
      }
    }
  }
}
