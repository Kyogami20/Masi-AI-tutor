package pe.masi.servicios

/**
 * Recuerda las sustituciones que se descartaron por dudosas, para reconocerlas si vuelven.
 *
 * **Existe para resolver una tensión que parecía irresoluble.** Cuando un niño lee "cuaderno" donde
 * dice "libro", las dos palabras se parecen tan poco que la política lo descarta: el criterio es que
 * una diferencia tan grande suele significar que el transcriptor oyó mal, no que el niño leyera mal.
 * Y ese criterio es correcto **para una sola muestra**.
 *
 * Pero adivinar la palabra entera por el contexto es justamente el error más característico de un
 * lector con dificultades. Descartarlo siempre significa que la palabra que peor lee es la única que
 * nunca va a practicar.
 *
 * Bajar el umbral no es la solución: reintroduce los falsos positivos que este proyecto lleva
 * evitando desde el principio. La solución es **pedir una segunda muestra**. Dos grabaciones
 * independientes, con el mismo micrófono y el mismo ruido de fondo, que producen exactamente la
 * misma sustitución, ya no son ruido: son evidencia. Un transcriptor que se equivoca lo hace de
 * formas distintas cada vez; un niño que no reconoce una palabra la lee igual las dos veces.
 *
 * Kotlin puro y sin Android, para poder probarlo.
 */
class MemoriaDeDudas {

  private var claveDelObjetivo: String? = null
  private val vistas = mutableSetOf<String>()

  /** Cuántas sustituciones dudosas se recuerdan ahora mismo. Para los tests y el log. */
  val cuantasRecordadas: Int
    get() = vistas.size

  /**
   * Registra los descartes dudosos de este intento y devuelve los que **ya se habían visto antes**.
   *
   * Cambiar de fragmento vacía la memoria: una duda sobre "libro" no dice nada sobre la frase
   * siguiente, y arrastrarla acabaría marcando errores por coincidencia.
   *
   * @param objetivo el fragmento o palabra que se estaba leyendo.
   * @return los errores confirmados por repetición. Vacío en el primer intento, siempre.
   */
  fun confirmarRepetidos(objetivo: String, descartados: List<Descartado>): List<ErrorLectura> {
    val clave = DetectorErrores.normalizar(objetivo)
    if (clave != claveDelObjetivo) {
      vistas.clear()
      claveDelObjetivo = clave
    }

    val confirmados = mutableListOf<ErrorLectura>()
    for (descartado in descartados) {
      // Solo las sustituciones con distancia excesiva. Los otros motivos de duda —lectura
      // incompleta, demasiadas discrepancias— hablan de la grabación entera y no de una palabra
      // concreta, así que repetirlos no confirma nada sobre ninguna.
      if (descartado.motivo != Motivo.DISTANCIA_EXCESIVA) continue
      if (descartado.error.dicho.isBlank()) continue

      val huella = huellaDe(descartado.error)
      if (!vistas.add(huella)) confirmados.add(descartado.error)
    }
    return confirmados
  }

  /** Se llama al pasar de fragmento o al terminar. */
  fun olvidar() {
    vistas.clear()
    claveDelObjetivo = null
  }

  /**
   * La identidad de una sustitución: qué palabra era y qué se oyó en su lugar.
   *
   * Normalizada con la misma regla de siempre, para que "Cuaderno" y "cuaderno" cuenten como la
   * misma respuesta. Si no, el niño podría repetir el mismo error dos veces y no confirmarse nunca.
   */
  private fun huellaDe(error: ErrorLectura): String =
    DetectorErrores.normalizar(error.esperado) + "→" + DetectorErrores.normalizar(error.dicho)
}
