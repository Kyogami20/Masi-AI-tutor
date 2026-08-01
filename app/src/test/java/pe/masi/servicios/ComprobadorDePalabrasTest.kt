package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La comprobación que hace que el cuento merezca ser agéntico.
 *
 * Si esto se equivoca, el modelo reescribe un cuento que ya estaba bien —o da por bueno uno al que
 * le faltan las palabras que eran el motivo de generarlo—. Es la única señal externa y fiable que
 * tiene el bucle, así que tiene que ser exacta.
 */
class ComprobadorDePalabrasTest {

  private val cuento =
    "Rosa caminaba por la chacra. El perro corría detrás de ella. " +
      "Encontraron un nido entre las piedras."

  @Test
  fun `encuentra las palabras que estan de verdad`() {
    val c = ComprobadorDePalabras.comprobar(cuento, listOf("perro", "chacra"))
    assertEquals(listOf("perro", "chacra"), c.incluidas)
    assertTrue(c.faltan.isEmpty())
    assertTrue(c.completa)
  }

  @Test
  fun `las que faltan salen en la lista`() {
    val c = ComprobadorDePalabras.comprobar(cuento, listOf("perro", "montaña", "escuela"))
    assertEquals(listOf("perro"), c.incluidas)
    assertEquals(listOf("montaña", "escuela"), c.faltan)
    assertFalse(c.completa)
  }

  @Test
  fun `las tildes y las mayusculas no despistan`() {
    // El modelo escribe "Estableció" al empezar la frase; la tarjeta guarda "establecio".
    val c =
      ComprobadorDePalabras.comprobar(
        "Estableció su casa en la sierra. Allí vivía con su mamá.",
        listOf("estableció", "mamá"),
      )
    assertTrue("debía encontrar las dos: ${c.faltan}", c.completa)
  }

  @Test
  fun `no confunde una palabra con el trozo de otra`() {
    // El fallo evidente si se usara `contains`: "sol" aparece dentro de "solo" y "soledad".
    val c = ComprobadorDePalabras.comprobar("Se quedó solo en la soledad del cerro.", listOf("sol"))
    assertEquals(listOf("sol"), c.faltan)
  }

  @Test
  fun `la puntuacion pegada no impide encontrarla`() {
    val c = ComprobadorDePalabras.comprobar("Corrió hacia el río, y se detuvo.", listOf("río"))
    assertTrue(c.completa)
  }

  @Test
  fun `suficiente se activa al llegar al umbral`() {
    // 3 de 5 = 0.6, justo el umbral: vale. Reescribir por las dos que faltan costaría 20 s más y
    // forzaría el cuento.
    val c =
      ComprobadorDePalabras.comprobar(
        cuento,
        listOf("perro", "chacra", "piedras", "montaña", "escuela"),
      )
    assertEquals(3, c.incluidas.size)
    assertTrue("3 de 5 debería bastar", c.suficiente)
    assertFalse("pero no está completo", c.completa)
  }

  @Test
  fun `suficiente no se activa por debajo del umbral`() {
    val c =
      ComprobadorDePalabras.comprobar(cuento, listOf("perro", "montaña", "escuela", "avión"))
    assertEquals(1, c.incluidas.size)
    assertFalse("1 de 4 no basta", c.suficiente)
  }

  @Test
  fun `sin palabras que buscar no hay nada que reescribir`() {
    val c = ComprobadorDePalabras.comprobar(cuento, emptyList())
    assertTrue(c.suficiente)
    assertTrue(c.completa)
  }
}
