package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La alineación entre lo que se compara y lo que se muestra.
 *
 * El invariante de este archivo es lo que impide dos fallos distintos:
 *  - que la tarjeta enseñe la ortografía mal ("establecio" por "estableció");
 *  - que el índice de un error apunte a la palabra de al lado, y se marque una palabra que el niño
 *    leyó bien.
 */
class PalabrasConOriginalTest {

  private val textos =
    listOf(
      "Durante la guerra federal de los Estados Unidos se estableció en Baltimore.",
      "El Sr. Barbicane vivía en EE.UU. desde niño.",
      "¿Dónde está mi mamá? ¡Aquí!",
      "El número pi vale 3.14 y la cuerda medía 1,5 metros.",
      "azúcar, corazón; árbol.",
      "una sola",
      "",
    )

  @Test
  fun `la forma normalizada coincide siempre con la que se compara`() {
    for (texto in textos) {
      assertEquals(
        "desalineado en: $texto",
        DetectorErrores.palabras(texto),
        DetectorErrores.palabrasConOriginal(texto).map { it.normalizada },
      )
    }
  }

  @Test
  fun `conserva las tildes y las mayusculas de la escritura`() {
    val palabras = DetectorErrores.palabrasConOriginal("se estableció en Baltimore.")
    assertEquals(listOf("se", "estableció", "en", "Baltimore"), palabras.map { it.escritura })
    // Y para comparar sigue usándose la forma sin tildes, que es la que aguanta una transcripción.
    assertEquals(listOf("se", "establecio", "en", "baltimore"), palabras.map { it.normalizada })
  }

  @Test
  fun `quita la puntuacion de los bordes pero no de dentro`() {
    val palabras = DetectorErrores.palabrasConOriginal("¡Corre, corre! ¿sí?")
    assertEquals(listOf("Corre", "corre", "sí"), palabras.map { it.escritura })
  }

  @Test
  fun `la enie se conserva, que ano y año son cosas distintas`() {
    val palabras = DetectorErrores.palabrasConOriginal("El año pasado.")
    assertEquals(listOf("El", "año", "pasado"), palabras.map { it.escritura })
    assertEquals(listOf("el", "año", "pasado"), palabras.map { it.normalizada })
  }

  @Test
  fun `un texto vacio no da palabras`() {
    assertEquals(emptyList<PalabraOriginal>(), DetectorErrores.palabrasConOriginal("   "))
  }
}
