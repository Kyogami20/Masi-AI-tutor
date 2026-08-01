package pe.masi.servicios

/**
 * Quita de la transcripción el trozo donde el modelo se repite a sí mismo el enunciado.
 *
 * **Este es el fallo que hacía que Masi felicitara por cualquier cosa**, y no tenía nada que ver con
 * los umbrales ni con la política. Medido en el teléfono, 5 de 8 escuchas seguidas salieron así:
 *
 * ```
 * Decía:  "MI LIBRO DE HISTORIAS BÍBLICAS"
 * Se oyó: "mi cuaderno de historias bíblicas el niño debía leer exactamente esto
 *          mi libro de historias bíblicas"
 * ```
 *
 * El modelo transcribe bien —"cuaderno" está ahí, literal, que era justo lo que se dudaba— y a
 * continuación **sigue escribiendo el prompt de turno**, incluida una copia exacta de la frase que
 * tocaba leer. Es un modelo pequeño continuando el texto que tiene delante; no es un fallo de audio.
 *
 * Y esa copia exacta al final envenena la comparación de una forma que no se ve venir. El alineador
 * busca el camino más barato entre lo esperado y lo dicho, y con el eco pegado detrás siempre existe
 * uno mejor que el correcto:
 *
 * ```
 * alinear con el principio:  1 sustitución (libro→cuaderno) + 11 inserciones = 12
 * alinear con el eco final:  0 sustituciones               + 11 inserciones = 11  ← gana
 * ```
 *
 * Las inserciones no se marcan como error —y no deben marcarse: que el niño diga una palabra de más
 * no es un fallo de lectura— así que el resultado era cero errores. "¡Muy bien!". Por un punto de
 * coste.
 *
 * Se corta aquí, antes de comparar, y en código determinista. Arreglar el prompt para que el modelo
 * no se repita se intenta también, pero no puede ser la garantía: un modelo pequeño de 2B efectivos
 * divaga, y el resto de la app no puede depender de que hoy no le apetezca.
 *
 * Devuelve el texto ya normalizado porque quien lo consume ([PoliticaConservadora]) lo normaliza de
 * todas formas. La transcripción cruda se conserva aparte, para la pantalla de diagnóstico.
 */
object LimpiadorDeEco {

  /**
   * Trozos del prompt de turno que el modelo copia al continuarse.
   *
   * Van normalizados y **sin la primera palabra** cuando esta puede quedar pegada a la anterior: en
   * el teléfono salió `"...del kibroEl niño debía leer..."`, todo junto, así que buscar "el niño
   * debía" no habría encontrado nada. Buscar "nino debia leer" sí.
   *
   * Si mañana cambia [pe.masi.motor.Prompts.turnoEscucha], esta lista hay que revisarla — pero
   * incluso desactualizada del todo, la regla del eco literal y la guarda de desbordamiento de
   * [PoliticaConservadora] siguen cubriendo el caso.
   */
  private val MARCAS =
    listOf(
      // Con "el" delante primero, para que el corte se lleve también el artículo. Y con ñ y sin
      // ella: `normalizar` conserva la ñ a propósito —"año" y "ano" son palabras distintas— así que
      // el texto llega con "niño", pero el modelo a veces la pierde.
      //
      // Que la variante con "el" vaya la primera resuelve además el caso pegado del log: en
      // "...del kibroEl niño debía leer", el corte cae dentro de "kibroel" y deja "kibro" limpio,
      // que es exactamente la palabra mal leída y acaba siendo tarjeta.
      "el niño debia leer",
      "el nino debia leer",
      "niño debia leer",
      "nino debia leer",
      "escucha el audio",
      "transcribe literalmente",
      "devuelve solo",
      "eres un transcriptor",
    )

  /**
   * Frases más cortas que esto no usan la regla del eco literal.
   *
   * En Practicar el objetivo es una sola palabra, y buscarla repetida dentro de la transcripción
   * corta por cualquier sitio: con objetivo "oso", un "el oso hermoso" se quedaría en "el". Con tres
   * palabras o más, una copia literal ya no es casualidad.
   */
  private const val MINIMO_PARA_ECO = 3

  fun limpiar(textoEsperado: String, textoDicho: String): String {
    val texto = DetectorErrores.normalizar(textoDicho)
    if (texto.isEmpty()) return texto

    val corte =
      listOfNotNull(cortePorMarca(texto), cortePorEco(texto, textoEsperado)).minOrNull()
        ?: return texto

    return texto.take(corte).trim()
  }

  /** Dónde empieza el prompt repetido, si es que empieza. */
  private fun cortePorMarca(texto: String): Int? =
    MARCAS.mapNotNull { marca -> texto.indexOf(marca).takeIf { it > 0 } }.minOrNull()

  /**
   * Dónde empieza una copia literal de la frase que tocaba leer, si no es al principio.
   *
   * La red que no depende de conocer el prompt. Se exige que caiga en frontera de palabra: sin eso,
   * buscar "oso" cortaría por la mitad de "hermoso".
   *
   * Que empiece en 0 es el caso bueno —el niño leyó bien— y ahí no hay nada que cortar.
   */
  private fun cortePorEco(texto: String, textoEsperado: String): Int? {
    val objetivo = DetectorErrores.normalizar(textoEsperado)
    if (objetivo.isEmpty()) return null
    val palabrasObjetivo = objetivo.count { it == ' ' } + 1
    if (palabrasObjetivo < MINIMO_PARA_ECO) return null

    var desde = 1
    while (true) {
      val i = texto.indexOf(objetivo, desde)
      if (i < 0) return null
      // Delante tiene que haber ya una lectura entera. Si no, esto no es una repetición: es el niño
      // arrancando con una palabra de más, y cortar ahí se comería la lectura de verdad.
      val palabrasDelante = texto.take(i).trim().let { if (it.isEmpty()) 0 else it.count { c -> c == ' ' } + 1 }
      if (texto[i - 1] == ' ' && palabrasDelante >= palabrasObjetivo) return i
      desde = i + 1
    }
  }
}
