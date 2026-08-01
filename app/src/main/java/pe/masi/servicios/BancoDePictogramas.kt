package pe.masi.servicios

/**
 * El catálogo de pictogramas: palabra normalizada → nombre de archivo en `assets/pictogramas/`.
 *
 * Se separa de la carga del archivo a propósito, para poder probar la búsqueda en la JVM con un
 * índice inventado, sin `Context` ni assets.
 */
class BancoDePictogramas(private val indice: Map<String, String>) {

  val cuantos: Int
    get() = indice.size

  /**
   * Busca el pictograma de un concepto.
   *
   * La normalización es la misma que usa la detección de errores ([DetectorErrores.normalizar]), y
   * tiene que serlo: el índice se generó con esa misma regla desde Python. Si las dos se
   * desalinean, las búsquedas fallan en silencio, que es la peor forma de fallar.
   *
   * Se intenta también en singular. Un modelo pide "montañas" o "perros" con toda naturalidad, y
   * tener dos entradas por palabra en el banco sería desperdiciar espacio para nada.
   */
  fun buscar(concepto: String): String? {
    val clave = DetectorErrores.normalizar(concepto).trim()
    if (clave.isEmpty()) return null

    indice[clave]?.let { return it }

    // Plural del español: -es tras consonante, -s tras vocal. Basta con probar quitando.
    if (clave.endsWith("es") && clave.length > 4) indice[clave.dropLast(2)]?.let { return it }
    if (clave.endsWith("s") && clave.length > 3) indice[clave.dropLast(1)]?.let { return it }

    // El modelo pidió "el perro" o "un campo grande": se prueba palabra a palabra, saltándose
    // artículos y preposiciones por longitud.
    if (clave.contains(' ')) {
      for (p in clave.split(' ')) {
        if (p.length > 2) indice[p]?.let { return it }
      }
    }

    // Última oportunidad: la palabra derivada de una que sí está. Medido en el teléfono, el modelo
    // busca "tristeza" cuando el banco tiene "triste", y "cansancio" cuando tiene "cansado". Se
    // exige un prefijo largo (5 caracteres) para no emparejar cosas que solo comparten el principio:
    // "casa" y "casi" no deben confundirse.
    if (clave.length >= MIN_RAIZ) {
      indice.keys
        .filter { it.length >= MIN_RAIZ && clave.startsWith(it) }
        .maxByOrNull { it.length }
        ?.let { return indice[it] }
    }
    return null
  }

  fun tiene(concepto: String): Boolean = buscar(concepto) != null

  companion object {
    /** Prefijo mínimo para dar por buena una derivación. Por debajo, los falsos positivos mandan. */
    private const val MIN_RAIZ = 5

    /** Ruta dentro de `assets`. La usa la interfaz para cargar la imagen. */
    fun rutaDe(archivo: String): String = "pictogramas/$archivo"

    val VACIO = BancoDePictogramas(emptyMap())
  }
}
