package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El test más importante del proyecto después de los de detección de errores.
 *
 * Si el segmentador pierde texto, el niño lee media página y cree que terminó. Si produce
 * fragmentos de una palabra, la sesión se vuelve absurda. Ambas cosas pasaron de verdad con el
 * `chunked(8)` anterior, y por eso el invariante de cobertura está fijado aquí.
 */
class SegmentadorTest {

  /** La página real con la que se detectó el fallo: "De la Tierra a la Luna", de Julio Verne. */
  private val gunClub =
    "EL \"GUN CLUB\". Durante la guerra federal de los Estados Unidos se estableció en la " +
      "ciudad de Baltimore, en pleno Maryland, un club muy influyente. Es sabido con qué " +
      "energía brotó el instinto militar en aquel pueblo de armadores, comerciantes y " +
      "mecánicos. Simples negociantes saltaron de sus mostradores y se improvisaron capitanes."

  private fun palabras(fragmento: String) = Segmentador.cuentaPalabras(fragmento)

  // --- El invariante ----------------------------------------------------------------------------

  @Test
  fun `unir los fragmentos devuelve el texto completo`() {
    for (texto in listOf(gunClub, CON_PISTAS, SIN_PUNTUACION, UNA_SOLA_ORACION_LARGA)) {
      val fragmentos = Segmentador.fragmentar(texto)
      assertEquals(
        "no se puede perder ni una palabra del texto",
        Segmentador.limpiar(texto),
        fragmentos.joinToString(" "),
      )
    }
  }

  @Test
  fun `ningun fragmento se pasa del tope de palabras`() {
    for (texto in listOf(gunClub, CON_PISTAS, SIN_PUNTUACION, UNA_SOLA_ORACION_LARGA)) {
      val largos = Segmentador.fragmentar(texto).filter { palabras(it) > Segmentador.MAX_PALABRAS }
      assertTrue("estos no caben en una grabación: $largos", largos.isEmpty())
    }
  }

  @Test
  fun `no quedan esquirlas de una palabra`() {
    // Un fragmento corto solo se tolera si fusionarlo se pasaría del tope, y con este texto no
    // ocurre en ningún punto.
    val cortos = Segmentador.fragmentar(gunClub).filter { palabras(it) < Segmentador.MIN_PALABRAS }
    assertTrue("fragmentos huérfanos: $cortos", cortos.isEmpty())
  }

  // --- Cortes correctos -------------------------------------------------------------------------

  @Test
  fun `una oracion larga se corta por la coma, no cada diez palabras a ciegas`() {
    val fragmentos =
      Segmentador.fragmentar(
        "Durante la guerra federal de los Estados Unidos se estableció en la ciudad " +
          "de Baltimore, en pleno Maryland, un club muy influyente."
      )
    assertTrue("debía partirse en varios", fragmentos.size >= 2)
    // Alguna de las dos comas tiene que haberse aprovechado como costura.
    assertTrue(
      "ningún fragmento cerró en coma: $fragmentos",
      fragmentos.any { it.endsWith(",") },
    )
  }

  @Test
  fun `ningun fragmento termina en una palabra que anuncia lo que viene`() {
    // "…de los Estados" deja al niño colgado a media construcción.
    val colgados =
      Segmentador.fragmentar(gunClub).filter {
        it.trimEnd('.', ',', ';', ':', '!', '?', '"').split(' ').last().lowercase() in
          setOf("de", "la", "el", "los", "las", "en", "un", "una", "y", "que", "por", "con", "muy")
      }
    assertTrue("fragmentos cortados a media construcción: $colgados", colgados.isEmpty())
  }

  @Test
  fun `no parte un nombre propio compuesto`() {
    // Caso real: el corte por el centro caía justo entre "Estados" y "Unidos".
    val fragmentos =
      Segmentador.fragmentar(
        "Durante la guerra federal de los Estados Unidos se estableció un club influyente."
      )
    assertTrue(
      "se partió 'Estados Unidos': $fragmentos",
      fragmentos.none { it.trimEnd('.', ',').endsWith("Estados") },
    )
  }

  @Test
  fun `cada oracion corta se queda entera`() {
    val fragmentos = Segmentador.fragmentar("El perro corre. La gata duerme en la cama.")
    assertEquals(listOf("El perro corre.", "La gata duerme en la cama."), fragmentos)
  }

  @Test
  fun `la pista del modelo se respeta y no llega a la pantalla`() {
    val fragmentos = Segmentador.fragmentar("El perro corre|La gata duerme en la cama")
    assertEquals(listOf("El perro corre", "La gata duerme en la cama"), fragmentos)
    assertTrue("el separador nunca se muestra", fragmentos.none { it.contains('|') })
  }

  @Test
  fun `sin la pista, un texto bien puntuado se trocea igual`() {
    val con = Segmentador.fragmentar("El perro corre.|La gata duerme en la cama.")
    val sin = Segmentador.fragmentar("El perro corre. La gata duerme en la cama.")
    assertEquals(sin, con)
  }

  // --- Guardas ----------------------------------------------------------------------------------

  @Test
  fun `las abreviaturas no parten la oracion`() {
    assertEquals(
      listOf("El Sr. Barbicane era el presidente del club."),
      Segmentador.fragmentar("El Sr. Barbicane era el presidente del club."),
    )
    assertEquals(
      listOf("Vivían en EE.UU. desde niños."),
      Segmentador.fragmentar("Vivían en EE.UU. desde niños."),
    )
  }

  @Test
  fun `una inicial no parte la oracion`() {
    assertEquals(
      listOf("El libro es de J. Verne y es muy antiguo."),
      Segmentador.fragmentar("El libro es de J. Verne y es muy antiguo."),
    )
  }

  @Test
  fun `los numeros con punto o coma no parten la oracion`() {
    assertEquals(
      listOf("El número pi vale 3.14 y no cambia nunca."),
      Segmentador.fragmentar("El número pi vale 3.14 y no cambia nunca."),
    )
    assertEquals(
      listOf("La cuerda medía 1,5 metros de largo."),
      Segmentador.fragmentar("La cuerda medía 1,5 metros de largo."),
    )
  }

  // --- Bordes -----------------------------------------------------------------------------------

  @Test
  fun `un texto vacio no da fragmentos`() {
    assertEquals(emptyList<String>(), Segmentador.fragmentar(""))
    assertEquals(emptyList<String>(), Segmentador.fragmentar("   \n  "))
  }

  @Test
  fun `una sola palabra sigue siendo un fragmento`() {
    // No hay vecino con el que fusionarla, y dejar al niño sin nada que leer sería peor.
    assertEquals(listOf("Baltimore"), Segmentador.fragmentar("Baltimore"))
  }

  @Test
  fun `un texto sin ninguna puntuacion se trocea igual`() {
    val fragmentos = Segmentador.fragmentar(SIN_PUNTUACION)
    assertTrue("debía trocearse", fragmentos.size >= 2)
    assertTrue(fragmentos.all { palabras(it) <= Segmentador.MAX_PALABRAS })
  }

  private companion object {
    const val CON_PISTAS =
      "EL GUN CLUB|Durante la guerra federal de los Estados Unidos|se estableció en la ciudad " +
        "de Baltimore, en pleno Maryland, un club muy influyente"

    const val SIN_PUNTUACION =
      "durante la guerra federal de los estados unidos se estableció en la ciudad de baltimore " +
        "en pleno maryland un club muy influyente que reunía a muchos socios"

    const val UNA_SOLA_ORACION_LARGA =
      "Es sabido con qué energía brotó el instinto militar en aquel pueblo de armadores, " +
        "comerciantes y mecánicos, que saltaron de sus mostradores para improvisarse capitanes, " +
        "coroneles y generales sin haber pasado por la escuela de West Point."
  }
}
