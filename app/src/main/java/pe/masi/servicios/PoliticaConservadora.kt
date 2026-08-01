package pe.masi.servicios

/** Por qué una discrepancia detectada NO se le enseña al niño. */
enum class Motivo {
  /** No llegó transcripción, o el JSON del modelo no se pudo parsear. */
  TRANSCRIPCION_VACIA,

  /** El niño leyó bastante menos de lo escrito: se cortó, o se cortó la grabación. */
  LECTURA_INCOMPLETA,

  /** Tantas diferencias que lo más probable es que falle la transcripción, no el niño. */
  DEMASIADAS_DISCREPANCIAS,

  /** Lo dicho no se parece en nada a lo esperado: eso no es un error de lectura. */
  DISTANCIA_EXCESIVA,

  /** La diferencia se explica por el acento andino, no por un error de lectura. */
  VARIACION_ANDINA,

  /** Palabra demasiado corta como para fiarse de la transcripción. */
  PALABRA_MUY_CORTA,
}

data class Descartado(val error: ErrorLectura, val motivo: Motivo)

data class ResultadoLectura(
  /** Lo que SÍ se marca y llega al niño. */
  val errores: List<ErrorLectura>,

  /** Lo que se descartó y por qué. No se muestra al niño; sirve para depurar y para el adulto. */
  val descartados: List<Descartado>,

  /** Si es false, Masi no corrige nada y pide repetir con cariño. */
  val transcripcionFiable: Boolean,
)

/**
 * Decide qué discrepancias se convierten en corrección y cuáles se dejan pasar.
 *
 * Esta clase es el corazón ético del proyecto, no una optimización.
 *
 * El riesgo principal de Masi ya no es "no detecta errores" —eso está medido y funciona—, sino
 * "detecta un error que no existía". Marcar como fallo la lectura correcta de un niño le enseña que
 * es malo leyendo, y ese daño dura años. Además, el español andino tiene realizaciones vocálicas
 * distintas del limeño, así que un modelo puede marcar como error de lectura lo que es simplemente
 * el acento del niño: exactamente el daño que este proyecto dice combatir.
 *
 * De ahí la regla que gobierna todo el archivo: **ante la duda, no se marca error.** Es preferible
 * dejar pasar un fallo real —el niño lo repetirá otro día— a inventar uno falso.
 */
object PoliticaConservadora {

  /** Más de esto en una sola frase y se asume que quien falla es la transcripción. */
  const val MAX_DISCREPANCIAS = 2

  /** Por debajo de esta fracción de palabras leídas, la grabación no sirve para evaluar. */
  const val FRACCION_MINIMA_LEIDA = 0.5

  /** Palabras más cortas que esto no se marcan, salvo inversión limpia. */
  const val LONGITUD_MINIMA = 3

  /**
   * Mapa de neutralización vocálica del español andino.
   *
   * El contacto con el quechua, que tiene tres vocales, hace que /e/~/i/ y /o/~/u/ se realicen de
   * forma intercambiable en el habla andina. "Pelota" pronunciada "pilota" no es un error de
   * lectura: es un acento. Se colapsan las dos parejas y se comparan las palabras así.
   */
  private fun neutralizarVocales(palabra: String): String =
    palabra.map { c ->
        when (c) {
          'e' -> 'i'
          'o' -> 'u'
          else -> c
        }
      }
      .joinToString("")

  /** true si la única diferencia entre las dos palabras es la variación vocálica andina. */
  fun esVariacionAndina(esperado: String, dicho: String): Boolean {
    if (esperado == dicho) return false
    return neutralizarVocales(DetectorErrores.normalizar(esperado)) ==
      neutralizarVocales(DetectorErrores.normalizar(dicho))
  }

  /**
   * Umbral de parecido. Si lo dicho se aleja más que esto de lo escrito, no es que el niño leyera
   * mal esa palabra: es que el transcriptor oyó otra cosa.
   */
  fun distanciaAceptable(esperado: String, dicho: String): Boolean {
    val limite = maxOf(2, esperado.length / 2)
    return DetectorErrores.distancia(esperado, dicho) <= limite
  }

  /**
   * @param textoEsperado lo que estaba escrito en la pantalla.
   * @param textoDicho la transcripción literal devuelta por el agente ESCUCHA. Cadena vacía si el
   *   modelo falló o el JSON no se pudo parsear.
   */
  fun evaluar(textoEsperado: String, textoDicho: String): ResultadoLectura {
    val esperadas = DetectorErrores.palabras(textoEsperado)
    val dichas = DetectorErrores.palabras(textoDicho)

    // Regla 4: sin transcripción no se corrige nada.
    if (esperadas.isEmpty() || dichas.isEmpty()) {
      return ResultadoLectura(emptyList(), emptyList(), transcripcionFiable = false)
    }

    // El niño se detuvo, o la grabación se cortó. Marcar todo lo que falta como omisiones sería
    // castigarlo por algo que no hizo.
    if (dichas.size < esperadas.size * FRACCION_MINIMA_LEIDA) {
      val todas =
        DetectorErrores.comparar(textoEsperado, textoDicho).map {
          Descartado(it, Motivo.LECTURA_INCOMPLETA)
        }
      return ResultadoLectura(emptyList(), todas, transcripcionFiable = false)
    }

    val brutos = DetectorErrores.comparar(textoEsperado, textoDicho)

    // Regla 3: demasiadas diferencias ⇒ la transcripción no es de fiar. No se marca ninguna.
    if (brutos.size > MAX_DISCREPANCIAS) {
      return ResultadoLectura(
        errores = emptyList(),
        descartados = brutos.map { Descartado(it, Motivo.DEMASIADAS_DISCREPANCIAS) },
        transcripcionFiable = false,
      )
    }

    val aceptados = mutableListOf<ErrorLectura>()
    val descartados = mutableListOf<Descartado>()

    for (error in brutos) {
      val motivo = motivoDeDescarte(error)
      if (motivo == null) aceptados.add(error) else descartados.add(Descartado(error, motivo))
    }

    return ResultadoLectura(aceptados, descartados, transcripcionFiable = true)
  }

  /** El motivo por el que este error no se marca, o `null` si sí se marca. */
  private fun motivoDeDescarte(error: ErrorLectura): Motivo? {
    // Una omisión limpia es fiable: la palabra no sonó y punto.
    if (error.tipo == TipoError.OMISION) {
      return if (error.esperado.length < LONGITUD_MINIMA) Motivo.PALABRA_MUY_CORTA else null
    }

    if (esVariacionAndina(error.esperado, error.dicho)) return Motivo.VARIACION_ANDINA

    // Las inversiones son el error de lectura más característico y más inequívoco: las mismas
    // letras en otro orden no salen de una transcripción torcida. Se aceptan aunque la palabra
    // sea corta ("el" leído "le" es material de manual).
    if (error.tipo == TipoError.INVERSION) return null

    if (error.esperado.length < LONGITUD_MINIMA) return Motivo.PALABRA_MUY_CORTA
    if (!distanciaAceptable(error.esperado, error.dicho)) return Motivo.DISTANCIA_EXCESIVA

    return null
  }
}
