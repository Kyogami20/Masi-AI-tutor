package pe.masi.servicios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los casos de este archivo salieron de pruebas en un teléfono real, no de la imaginación.
 *
 * Gemma 4 E2B produjo esas tres pistas para esas tres palabras. Ninguna sirve, y una pista que no
 * sirve es peor que ninguna: el niño no aprende nada y encima se desconcierta.
 */
class FiltroDePistasTest {

  @Test
  fun `rechaza la mezcla de idiomas vista en el dispositivo`() {
    assertFalse(FiltroDePistas.esUtil("¡Buen try! Lee la pelota", "gun", "gun"))
    assertFalse(FiltroDePistas.esUtil("¡Buen try! Mira el pan", "establecio", "es-ta-ble-cio"))
  }

  @Test
  fun `rechaza la pista que no habla de la palabra`() {
    // Sin inglés, pero igual de inútil: no dice nada de "contribuye".
    assertFalse(
      FiltroDePistas.esUtil("Qué bien lo hiciste! Mira el pan.", "contribuye", "con-tri-bu-ye")
    )
  }

  @Test
  fun `acepta una pista que nombra la palabra`() {
    assertTrue(
      FiltroDePistas.esUtil(
        "¡Muy bien por intentarlo! Dice pe-rro, empieza con la p de pan.",
        "perro",
        "pe-rro",
      )
    )
  }

  @Test
  fun `acepta una pista que usa las silabas`() {
    assertTrue(
      FiltroDePistas.esUtil(
        "¡Casi lo tienes! Suena con-tri, y después bu-ye.",
        "contribuye",
        "con-tri-bu-ye",
      )
    )
  }

  @Test
  fun `no confunde el espanol corriente con ingles`() {
    // "no" es español de uso diario. Si estuviera en la lista de inglés, el filtro rechazaría
    // casi todas las pistas válidas y el banco de repuesto saltaría siempre.
    assertTrue(
      FiltroDePistas.esUtil("¡Bien! Esta palabra no es difícil: mira, dice ca-sa.", "casa", "ca-sa")
    )
    assertFalse(FiltroDePistas.INGLES.contains("no"))
    assertFalse(FiltroDePistas.INGLES.contains("yes"))
  }

  @Test
  fun `rechaza el lenguaje de deficit`() {
    assertFalse(
      FiltroDePistas.esUtil("Está mal, dice pe-rro y tú dijiste otra cosa.", "perro", "pe-rro")
    )
  }

  @Test
  fun `rechaza lo demasiado corto o demasiado largo`() {
    assertFalse(FiltroDePistas.esUtil("perro", "perro", "pe-rro"))
    assertFalse(FiltroDePistas.esUtil("pe-rro ".repeat(60), "perro", "pe-rro"))
  }
}
