package pe.masi.servicios

/** Por qué una discrepancia detectada NO se le enseña al niño. */
/**
 * Por qué no se marcó una discrepancia. **No todos significan lo mismo, y confundirlos fue un fallo
 * grave.**
 *
 * Hay dos familias, y la diferencia es de significado, no de implementación:
 *
 *  - **"No es un error"**: la discrepancia se explica y el niño leyó bien. Felicitarlo es correcto.
 *  - **"No lo sé"**: hay una diferencia real y no sabemos de quién es la culpa. Aquí no se puede
 *    marcar nada —sería inventar un error— pero **tampoco se puede felicitar**.
 *
 * Durante mucho tiempo se trataron igual, y el resultado fue que un niño que leyó "cuaderno" donde
 * decía "libro" recibía un "¡Muy bien!": la sustitución se descartaba por [DISTANCIA_EXCESIVA] y la
 * lista de errores quedaba vacía, que aguas abajo se leía como acierto.
 *
 * @param esDuda si el descarte significa incertidumbre y no lectura correcta.
 */
enum class Motivo(val esDuda: Boolean) {
  /** No llegó transcripción, o el JSON del modelo no se pudo parsear. */
  TRANSCRIPCION_VACIA(esDuda = true),

  /** El niño leyó bastante menos de lo escrito: se cortó, o se cortó la grabación. */
  LECTURA_INCOMPLETA(esDuda = true),

  /** Tantas diferencias que lo más probable es que falle la transcripción, no el niño. */
  DEMASIADAS_DISCREPANCIAS(esDuda = true),

  /**
   * Llegó mucho más texto del que cabía en la frase.
   *
   * La firma de que el modelo, en vez de transcribir y callarse, siguió escribiendo: normalmente
   * repitiéndose el propio enunciado. [LimpiadorDeEco] corta lo que reconoce; esto es la red por
   * debajo, para las divagaciones que no reconozca.
   */
  TRANSCRIPCION_DESBORDADA(esDuda = true),

  /**
   * Lo dicho no se parece a lo escrito.
   *
   * Se descarta porque suele ser el transcriptor oyendo otra cosa, **pero también es la firma del
   * error de lectura más grave**: adivinar la palabra entera por el contexto. No se puede marcar sin
   * más, y desde luego no se puede premiar.
   */
  DISTANCIA_EXCESIVA(esDuda = true),

  /**
   * La diferencia se explica por el acento andino, no por un error de lectura.
   *
   * **No es duda: es lectura correcta.** Un niño que dice "pilota" por "pelota" está leyendo bien
   * con su acento, y mandarle repetir sería castigar el habla andina — exactamente el daño que este
   * proyecto existe para no causar.
   */
  VARIACION_ANDINA(esDuda = false),

  /** Palabra demasiado corta como para fiarse. Se le da el beneficio de la duda al niño. */
  PALABRA_MUY_CORTA(esDuda = false),
}

data class Descartado(val error: ErrorLectura, val motivo: Motivo)

data class ResultadoLectura(
  /** Lo que SÍ se marca y llega al niño. */
  val errores: List<ErrorLectura>,

  /** Lo que se descartó y por qué. No se muestra al niño; sirve para depurar y para el adulto. */
  val descartados: List<Descartado>,

  /** Si es false, Masi no corrige nada y pide repetir con cariño. */
  val transcripcionFiable: Boolean,
) {
  /**
   * Si quedó alguna diferencia sin explicar.
   *
   * **Esto es lo que separa "leyó bien" de "no me enteré".** Sin errores marcados y sin dudas, el
   * niño leyó bien y hay que felicitarlo. Sin errores marcados pero CON dudas, lo honesto es pedir
   * que repita: no sabemos qué pasó, y aplaudir por si acaso es lo peor que se puede hacer en una
   * app que enseña a leer.
   *
   * Una transcripción no fiable cuenta como duda por sí sola, sin mirar los descartes. No es
   * redundante: hay caminos que devuelven `transcripcionFiable = false` con la lista de descartes
   * vacía —el desbordamiento, por ejemplo, donde el eco hace que no se detecte ni una diferencia—
   * y sin esta cláusula esos casos parecerían una lectura impecable.
   */
  val hayDudas: Boolean
    get() = !transcripcionFiable || descartados.any { it.motivo.esDuda }
}

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

  /**
   * Cuántas palabras pueden salir distintas antes de desconfiar de la transcripción entera.
   *
   * **Era un 2 fijo, y era demasiado poco.** En una frase de diez palabras, un niño con dificultades
   * de lectura falla tres o cuatro con toda naturalidad — es literalmente el perfil de usuario de
   * esta app — y con el tope antiguo esa lectura se descartaba entera: ni una tarjeta, y un "no te
   * escuché bien" que además era mentira.
   *
   * Ahora escala con la frase: la mitad de las palabras, y nunca menos de tres. Un fragmento de diez
   * admite cinco fallos; uno de cuatro, tres.
   *
   * Sigue habiendo tope, y no por cautela abstracta: por encima de la mitad de las palabras ya no se
   * puede distinguir un niño que lee muy mal de una transcripción que no ha entendido nada, y en esa
   * frontera marcar diez errores de golpe es lo peor que puede hacer una app que enseña a leer.
   */
  fun maxDiscrepancias(palabrasEsperadas: Int): Int = maxOf(3, palabrasEsperadas / 2)

  /**
   * Cuánto más largo que la frase puede venir lo transcrito antes de no fiarse.
   *
   * Que el niño diga alguna palabra de más es normal y no se penaliza. Que lleguen dieciséis
   * palabras para una frase de cinco no es el niño hablando: es el modelo divagando.
   */
  const val FACTOR_MAXIMO_DICHO = 1.6

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

    // Llegó mucho más de lo que cabía. Ver [Motivo.TRANSCRIPCION_DESBORDADA]: sin esta guarda, el
    // texto de sobra le da al alineador un camino más barato que el correcto y los errores de verdad
    // desaparecen sin dejar rastro.
    if (dichas.size > esperadas.size * FACTOR_MAXIMO_DICHO + 2) {
      val todas =
        DetectorErrores.comparar(textoEsperado, textoDicho).map {
          Descartado(it, Motivo.TRANSCRIPCION_DESBORDADA)
        }
      return ResultadoLectura(emptyList(), todas, transcripcionFiable = false)
    }

    val brutos = DetectorErrores.comparar(textoEsperado, textoDicho)

    // Regla 3: demasiadas diferencias ⇒ la transcripción no es de fiar. No se marca ninguna.
    if (brutos.size > maxDiscrepancias(esperadas.size)) {
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
