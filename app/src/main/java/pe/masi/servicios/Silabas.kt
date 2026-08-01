package pe.masi.servicios

/**
 * Separación silábica del español, en código.
 *
 * El agente LECTOR ya devuelve el texto silabado, pero pedirle a un modelo que haga algo que tiene
 * reglas fijas es regalarle una oportunidad de equivocarse. Aquí está la versión determinista: se
 * usa para las tarjetas de repaso (donde hay una sola palabra y tiene que salir bien siempre) y
 * como red de seguridad cuando el modelo devuelve una separación rara.
 *
 * Las reglas implementadas son las del español estándar:
 *  - Los dígrafos `ch`, `ll` y `rr` son una sola consonante y no se parten.
 *  - Los grupos consonánticos con `l` o `r` (`pr`, `bl`, `tr`…) no se parten.
 *  - Entre dos vocales, una consonante sola pasa a la sílaba siguiente.
 *  - Dos vocales fuertes (a, e, o) hacen hiato y se separan; con una débil (i, u) hacen diptongo,
 *    salvo que la débil lleve tilde.
 */
object Silabas {

  private const val FUERTES = "aeoáéó"
  private const val DEBILES = "iuü"
  private const val DEBILES_TILDADAS = "íú"
  private const val VOCALES = FUERTES + DEBILES + DEBILES_TILDADAS

  private val DIGRAFOS = setOf("ch", "ll", "rr")
  private val INSEPARABLES =
    setOf("pr", "br", "tr", "dr", "cr", "gr", "fr", "pl", "bl", "cl", "gl", "fl")

  /** Devuelve la palabra con guiones entre sílabas: "murciélago" → "mur-cié-la-go". */
  fun separar(palabra: String): String {
    val limpia = palabra.trim()
    if (limpia.isEmpty()) return limpia
    return silabasDe(limpia).joinToString("-")
  }

  /** Igual que [separar], pero frase entera: separa cada palabra y conserva lo demás. */
  fun separarTexto(texto: String): String =
    Regex("""\p{L}+""").replace(texto) { separar(it.value) }

  /**
   * Las palabras de un texto que a un lector principiante le van a costar.
   *
   * El criterio es el del documento maestro: tres o más sílabas, o un grupo consonántico de los que
   * cuestan (br, pl, tr…). Es una regla fija, así que la aplica el código y no el modelo.
   */
  fun palabrasDificiles(texto: String, maximo: Int = 8): List<String> =
    Regex("""\p{L}+""")
      .findAll(texto)
      .map { it.value }
      .filter { it.length >= 4 }
      .filter { palabra ->
        val minuscula = palabra.lowercase()
        silabasDe(minuscula).size >= 3 || INSEPARABLES.any { minuscula.contains(it) }
      }
      .distinctBy { it.lowercase() }
      .take(maximo)
      .toList()

  fun silabasDe(palabra: String): List<String> {
    val original = palabra
    val unidades = tokenizar(original.lowercase())
    if (unidades.none { it.esVocal }) return listOf(original)

    val nucleos = agruparNucleos(unidades)
    if (nucleos.size <= 1) return listOf(original)

    // Índices (sobre `unidades`) donde empieza cada sílaba.
    val cortes = mutableListOf(0)
    for (i in 0 until nucleos.size - 1) {
      val finNucleo = nucleos[i].last
      val inicioSiguiente = nucleos[i + 1].first
      val consonantes = (finNucleo + 1) until inicioSiguiente
      cortes.add(puntoDeCorte(unidades, consonantes))
    }

    // Traducir los índices de unidad a índices de carácter del original.
    val inicios = unidades.map { it.inicio }
    return buildList {
      for (i in cortes.indices) {
        val desde = inicios[cortes[i]]
        val hasta = if (i + 1 < cortes.size) inicios[cortes[i + 1]] else original.length
        add(original.substring(desde, hasta))
      }
    }
  }

  // ---------------------------------------------------------------------------------------------

  private data class Unidad(val texto: String, val inicio: Int, val esVocal: Boolean)

  /** Trocea en letras, contando los dígrafos `ch`/`ll`/`rr` como una sola. */
  private fun tokenizar(palabra: String): List<Unidad> {
    val unidades = mutableListOf<Unidad>()
    var i = 0
    while (i < palabra.length) {
      val par = if (i + 1 < palabra.length) palabra.substring(i, i + 2) else null
      if (par != null && par in DIGRAFOS) {
        unidades.add(Unidad(par, i, esVocal = false))
        i += 2
      } else {
        val c = palabra[i]
        unidades.add(Unidad(c.toString(), i, esVocal = c in VOCALES))
        i++
      }
    }
    return unidades
  }

  /** Agrupa las vocales contiguas en núcleos silábicos, separando los hiatos. */
  private fun agruparNucleos(unidades: List<Unidad>): List<IntRange> {
    val nucleos = mutableListOf<IntRange>()
    var i = 0
    while (i < unidades.size) {
      if (!unidades[i].esVocal) {
        i++
        continue
      }
      var fin = i
      while (
        fin + 1 < unidades.size &&
          unidades[fin + 1].esVocal &&
          !hayHiato(unidades[fin].texto[0], unidades[fin + 1].texto[0])
      ) {
        fin++
      }
      nucleos.add(i..fin)
      i = fin + 1
    }
    return nucleos
  }

  /** true si las dos vocales pertenecen a sílabas distintas. */
  private fun hayHiato(v1: Char, v2: Char): Boolean {
    // Una débil con tilde siempre rompe el diptongo: "dí-a", "ba-úl".
    if (v1 in DEBILES_TILDADAS || v2 in DEBILES_TILDADAS) return true
    // Dos fuertes nunca se juntan: "le-er", "ca-os".
    return v1 in FUERTES && v2 in FUERTES
  }

  /**
   * Dónde empieza la sílaba siguiente, dado el bloque de consonantes que separa dos núcleos.
   *
   * @param consonantes rango de índices (posiblemente vacío) entre un núcleo y el siguiente.
   * @return índice de la unidad con la que arranca la sílaba siguiente.
   */
  private fun puntoDeCorte(unidades: List<Unidad>, consonantes: IntRange): Int {
    val n = consonantes.count()
    val primera = consonantes.first
    return when {
      // Hiato: el corte cae justo entre las dos vocales.
      n == 0 -> primera
      // Una sola consonante pasa entera a la sílaba siguiente: "ca-sa", "pe-rro".
      n == 1 -> primera
      n == 2 -> if (esInseparable(unidades, primera)) primera else primera + 1
      // Tres consonantes: si las dos últimas forman grupo, se van juntas ("ins-truc").
      n == 3 -> if (esInseparable(unidades, primera + 1)) primera + 1 else primera + 2
      // Cuatro o más: dos se quedan y el resto pasa ("cons-truc-ción").
      else -> primera + n - 2
    }
  }

  private fun esInseparable(unidades: List<Unidad>, indice: Int): Boolean {
    if (indice + 1 >= unidades.size) return false
    val grupo = unidades[indice].texto + unidades[indice + 1].texto
    return grupo in INSEPARABLES
  }
}
