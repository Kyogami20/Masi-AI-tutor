package pe.masi.servicios

/** Qué palabras del encargo aparecen de verdad en el cuento, y cuáles no. */
data class Cobertura(
  val incluidas: List<String>,
  val faltan: List<String>,
  /** Si ya vale la pena parar de reescribir. Ver [ComprobadorDePalabras.UMBRAL_SUFICIENTE]. */
  val suficiente: Boolean,
) {
  val completa: Boolean
    get() = faltan.isEmpty()
}

/**
 * Comprueba si un texto contiene unas palabras concretas.
 *
 * **Esta es la pieza que justifica que el cuento se genere con herramientas y no con un prompt.**
 * A un modelo de 2B se le pide que incluya cinco palabras y se deja dos; es un fallo conocido y aquí
 * es fatal, porque el cuento existe precisamente para que el niño lea *esas* palabras. Pedirle al
 * propio modelo que se autorevise no sirve: se equivoca en la revisión igual que en la redacción.
 *
 * Contar aquí, en Kotlin determinista, es exacto y gratis. El modelo llama a esta comprobación como
 * herramienta, recibe la lista de las que faltan y reescribe. **Ese bucle no cabe en una sola
 * pasada**, y lo ejecuta el runtime de LiteRT-LM sin que la app tenga que orquestarlo.
 *
 * Se compara con [DetectorErrores.normalizar] —la misma normalización que usa la detección de
 * errores de lectura— para que "Estableció", "estableció" y "establecio," cuenten como la misma
 * palabra. Sin eso, el modelo escribiría la palabra bien y la comprobación diría que falta.
 */
object ComprobadorDePalabras {

  /**
   * Con esta fracción de palabras ya se da por bueno el cuento.
   *
   * Perseguir el 100 % hace que el modelo reescriba una y otra vez forzando frases cada vez más
   * artificiales, y cada reescritura son ~20 s que el niño pasa mirando una pantalla de carga. Un
   * cuento natural con tres de cinco palabras enseña más que uno forzado con las cinco.
   */
  const val UMBRAL_SUFICIENTE = 0.6

  fun comprobar(texto: String, objetivo: List<String>): Cobertura {
    if (objetivo.isEmpty()) return Cobertura(emptyList(), emptyList(), suficiente = true)

    // Se compara palabra a palabra, no con `contains`: buscar "sol" dentro del texto encontraría
    // "solo" y daría por incluida una palabra que no está.
    val presentes = DetectorErrores.palabras(texto).toSet()

    val (incluidas, faltan) =
      objetivo.partition { DetectorErrores.normalizar(it) in presentes }

    return Cobertura(
      incluidas = incluidas,
      faltan = faltan,
      suficiente = incluidas.size.toDouble() / objetivo.size >= UMBRAL_SUFICIENTE,
    )
  }
}
