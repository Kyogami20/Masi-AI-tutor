package pe.masi.servicios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las palabras difíciles se calculan en código, no se le piden al modelo.
 *
 * Pedírselas costaba el triple de tokens de salida y hacía que la respuesta del LECTOR se cortara
 * a media frase. El criterio —tres o más sílabas, o un grupo consonántico— es una regla fija.
 */
class PalabrasDificilesTest {

  @Test
  fun `marca las palabras de tres o mas silabas`() {
    val dificiles = Silabas.palabrasDificiles("El murcielago come pan")
    assertTrue(dificiles.contains("murcielago"))
    assertFalse(dificiles.contains("pan"))
    assertFalse(dificiles.contains("El"))
  }

  @Test
  fun `marca los grupos consonanticos aunque la palabra sea corta`() {
    val dificiles = Silabas.palabrasDificiles("El plato y el libro")
    assertTrue(dificiles.contains("plato"))
    assertTrue(dificiles.contains("libro"))
  }

  @Test
  fun `no repite la misma palabra`() {
    val dificiles = Silabas.palabrasDificiles("La bicicleta y la Bicicleta")
    assertTrue(dificiles.size == 1)
  }

  @Test
  fun `deja fuera las palabras cortas y comunes`() {
    val dificiles = Silabas.palabrasDificiles("el sol es de dia y la luna de noche")
    assertTrue(dificiles.isEmpty())
  }

  @Test
  fun `respeta el maximo pedido`() {
    val texto = "murcielago bicicleta elefante mariposa telefono ventana computadora refrigerador"
    assertTrue(Silabas.palabrasDificiles(texto, maximo = 3).size == 3)
  }

  @Test
  fun `con texto vacio no revienta`() {
    assertTrue(Silabas.palabrasDificiles("").isEmpty())
  }
}
