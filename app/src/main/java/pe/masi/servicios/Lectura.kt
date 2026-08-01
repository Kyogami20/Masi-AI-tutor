package pe.masi.servicios

/** De dónde salió el texto que se está leyendo. */
enum class VersionTexto {
  /** Transcripción literal de la página, con sus palabras tal como están impresas. */
  FIEL,

  /**
   * Reescritura de Masi: términos complejos cambiados por otros más cortos y comunes.
   *
   * Todavía no se genera; el hueco está aquí para que activarla sea producir otra [Lectura] y no
   * tocar la pantalla de Escuchar.
   */
  CURADA,
}

/**
 * Un texto listo para leer acompañado: el contenido, cómo se trocea y cómo se separa en sílabas.
 *
 * Es el modelo de dominio de la app, y está deliberadamente separado de [PaginaLeida], que es solo
 * el JSON que devuelve el modelo. La frontera importa: lo que el modelo emite es una cadena y una
 * sugerencia de cortes; lo que la app garantiza —que los fragmentos cubren el texto entero, que
 * ninguno se pasa del tope de grabación— se calcula aquí en código determinista.
 *
 * Todo lo que consume la lectura (la pantalla de Escuchar, el recorrido de fragmentos, el repaso)
 * habla con esta clase y no con el LECTOR. Es lo que permitirá enchufar el texto curado, o un texto
 * guardado en la biblioteca, sin cambiar nada aguas abajo.
 */
data class Lectura(
  /** El texto tal como se le enseña al niño: sin marcas de segmentación, espacios normalizados. */
  val texto: String,

  /**
   * Lo que se lee de una en una.
   *
   * **Su unión reproduce [texto] completo.** No es un detalle de implementación: es lo que
   * garantiza que la sesión acompañe hasta el final de la página en vez de saltarse trozos.
   */
  val fragmentos: List<String>,

  /** [texto] con guiones entre sílabas, que es como se muestra en pantalla. */
  val silabas: String,

  /** Palabras que a un lector principiante le van a costar, por si hay que priorizarlas. */
  val dificiles: List<String> = emptyList(),
  val version: VersionTexto = VersionTexto.FIEL,
) {
  val estaVacia: Boolean
    get() = fragmentos.isEmpty()

  companion object {
    /**
     * Construye una lectura a partir del texto crudo del modelo.
     *
     * Aquí se aplican, en este orden, las tres reglas fijas del español que **no** se le piden al
     * modelo porque calcularlas en código es más rápido, exacto y gratis: limpiar las marcas,
     * trocear y separar en sílabas.
     */
    fun de(textoCrudo: String, version: VersionTexto = VersionTexto.FIEL): Lectura {
      val limpio = Segmentador.limpiar(textoCrudo)
      return Lectura(
        texto = limpio,
        fragmentos = Segmentador.fragmentar(textoCrudo),
        silabas = Silabas.separarTexto(limpio),
        dificiles = Silabas.palabrasDificiles(limpio),
        version = version,
      )
    }
  }
}
