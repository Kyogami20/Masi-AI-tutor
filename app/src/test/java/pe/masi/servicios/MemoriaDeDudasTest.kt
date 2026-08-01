package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo una duda se convierte en evidencia.
 *
 * El equilibrio que prueba este archivo es el más fino de la app. Marcar a la primera reintroduce
 * los falsos positivos que el proyecto lleva evitando desde el principio; no marcar nunca deja al
 * niño sin practicar precisamente la palabra que peor lee. La salida es pedir una segunda muestra, y
 * lo que hay que sujetar con tests es que **solo la repetición exacta cuenta**.
 */
class MemoriaDeDudasTest {

  private fun sustitucion(esperado: String, dicho: String) =
    Descartado(
      ErrorLectura(indice = 3, esperado = esperado, dicho = dicho, tipo = TipoError.SUSTITUCION),
      Motivo.DISTANCIA_EXCESIVA,
    )

  private val frase = "el niño abrió el libro"

  @Test
  fun `la primera vez nunca confirma nada`() {
    // Lo que protege del falso positivo. Una sola grabación con ruido no puede acusar a nadie.
    val memoria = MemoriaDeDudas()
    val confirmados = memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuaderno")))
    assertTrue(confirmados.isEmpty())
  }

  @Test
  fun `la misma sustitucion dos veces si confirma`() {
    // El caso reportado: "libro" leído "cuaderno" dos veces seguidas. Dos grabaciones distintas,
    // con el mismo micrófono y el mismo ruido de fondo, que dan exactamente el mismo resultado, ya
    // no son ruido del transcriptor: un transcriptor se equivoca distinto cada vez.
    val memoria = MemoriaDeDudas()
    memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuaderno")))
    val confirmados = memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuaderno")))

    assertEquals(1, confirmados.size)
    assertEquals("libro", confirmados.single().esperado)
    assertEquals("cuaderno", confirmados.single().dicho)
  }

  @Test
  fun `dos respuestas distintas para la misma palabra no confirman`() {
    // Aquí está la diferencia entre un niño y un micrófono. Si la primera vez se oyó "cuaderno" y
    // la segunda "cuadro", lo que varía es la transcripción, no la lectura.
    val memoria = MemoriaDeDudas()
    memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuaderno")))
    val confirmados = memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuadro")))

    assertTrue(confirmados.isEmpty())
  }

  @Test
  fun `mayusculas y tildes no rompen la coincidencia`() {
    // Sin esto, el niño repetiría el mismo error y no se confirmaría nunca porque el modelo escribió
    // la palabra con mayúscula inicial en una de las dos transcripciones.
    val memoria = MemoriaDeDudas()
    memoria.confirmarRepetidos(frase, listOf(sustitucion("pájaro", "Pajaro")))
    val confirmados = memoria.confirmarRepetidos(frase, listOf(sustitucion("Pájaro", "pajaro")))

    assertEquals(1, confirmados.size)
  }

  @Test
  fun `cambiar de frase vacia la memoria`() {
    // Arrastrar dudas entre fragmentos acabaría marcando errores por coincidencia, y encima con la
    // pinta de un fallo confirmado.
    val memoria = MemoriaDeDudas()
    memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuaderno")))
    val confirmados =
      memoria.confirmarRepetidos("otra frase distinta", listOf(sustitucion("libro", "cuaderno")))

    assertTrue(confirmados.isEmpty())
    assertEquals(1, memoria.cuantasRecordadas)
  }

  @Test
  fun `olvidar deja la memoria a cero`() {
    val memoria = MemoriaDeDudas()
    memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuaderno")))
    memoria.olvidar()

    assertEquals(0, memoria.cuantasRecordadas)
    assertTrue(memoria.confirmarRepetidos(frase, listOf(sustitucion("libro", "cuaderno"))).isEmpty())
  }

  @Test
  fun `los descartes que hablan de la grabacion entera no se recuerdan`() {
    // "Lectura incompleta" o "demasiadas discrepancias" describen la grabación, no una palabra.
    // Repetirlos no confirma nada sobre ninguna palabra concreta, y contarlos como evidencia sería
    // marcar errores por haber grabado mal dos veces.
    val memoria = MemoriaDeDudas()
    val global =
      Descartado(
        ErrorLectura(1, "libro", "cuaderno", TipoError.SUSTITUCION),
        Motivo.DEMASIADAS_DISCREPANCIAS,
      )
    memoria.confirmarRepetidos(frase, listOf(global))

    assertEquals(0, memoria.cuantasRecordadas)
    assertTrue(memoria.confirmarRepetidos(frase, listOf(global)).isEmpty())
  }
}
