package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El eco del enunciado, con las transcripciones reales que lo destaparon.
 *
 * Todas las cadenas de este archivo salieron del log del Redmi el 1 de agosto de 2026, copiadas tal
 * cual. No son casos inventados: son las ocho escuchas seguidas en las que Masi contestó "leyó bien"
 * a lecturas mal hechas a propósito.
 */
class LimpiadorDeEcoTest {

  @Test
  fun `corta el enunciado repetido y conserva el error de verdad`() {
    // El caso reportado. Sin cortar, la copia exacta del final le daba al alineador un camino de
    // coste 11 frente al correcto de 12, y "cuaderno" desaparecía sin dejar rastro.
    val limpio =
      LimpiadorDeEco.limpiar(
        "MI LIBRO DE HISTORIAS BÍBLICAS",
        "mi cuaderno de historias bíblicas el niño debía leer exactamente esto " +
          "mi libro de historias bíblicas",
      )

    assertEquals("mi cuaderno de historias biblicas", limpio)
  }

  @Test
  fun `despues de cortar, el error si se detecta`() {
    // La comprobación que de verdad importa: no que el texto quede bonito, sino que la política
    // vuelva a ver el error que antes se tragaba.
    val esperado = "MI LIBRO DE HISTORIAS BÍBLICAS"
    val crudo =
      "mi cuaderno de historias bíblicas el niño debía leer exactamente esto " +
        "mi libro de historias bíblicas"

    assertTrue("sin limpiar decía que leyó bien", PoliticaConservadora.evaluar(esperado, crudo).errores.isEmpty())

    val limpio = LimpiadorDeEco.limpiar(esperado, crudo)
    val r = PoliticaConservadora.evaluar(esperado, limpio)
    assertTrue("ahora al menos tiene que dudar", r.hayDudas || r.errores.isNotEmpty())
  }

  @Test
  fun `corta aunque el marcador venga pegado a la palabra anterior`() {
    // Salió literal en el log: "del kibroEl niño debía...", sin espacio. Por eso los marcadores no
    // empiezan por "el": buscar "el nino debia" aquí no habría encontrado nada.
    val limpio =
      LimpiadorDeEco.limpiar(
        "Se han tomado del libro",
        "Se han tomado del kibroEl niño debía leer exactamente esto: \"Se han tomado del libro\"",
      )

    // Y el corte cae dentro de "kibroEl", así que "kibro" sale limpio y llega a ser tarjeta.
    assertEquals("se han tomado del kibro", limpio)
  }

  @Test
  fun `una transcripcion limpia no se toca`() {
    // La mitad de las escuchas del log salieron bien. Ninguna puede empeorar por esto.
    assertEquals(
      "los relatos te dan una historia del mundo desde",
      LimpiadorDeEco.limpiar(
        "Los relatos te dan una historia del mundo desde",
        "los relatos te dan una historia del mundo desde",
      ),
    )
  }

  @Test
  fun `leer bien no se confunde con un eco`() {
    // La trampa evidente: cuando el niño lee correctamente, lo dicho ES la frase esperada. Si el
    // corte por eco no exigiera que empiece más allá del principio, se quedaría en nada.
    assertEquals(
      "este es un libro de historias reales",
      LimpiadorDeEco.limpiar("ESTE es un libro de historias reales.", "Este es un libro de historias reales."),
    )
  }

  @Test
  fun `con una sola palabra objetivo no se corta nada`() {
    // El caso de Practicar, donde el objetivo es una palabra suelta. Buscarla repetida dentro de la
    // transcripción cortaría por cualquier sitio: con objetivo "oso", esto se habría quedado en
    // "el" y la evaluación entera sería basura.
    assertEquals("el oso hermoso", LimpiadorDeEco.limpiar("oso", "el oso hermoso"))
  }

  @Test
  fun `una palabra de mas al principio no dispara el corte`() {
    // La otra forma de equivocarse: si el niño arranca con un titubeo, la frase de verdad empieza
    // más allá del principio, y cortar ahí se comería justo lo que hay que evaluar.
    assertEquals(
      "eh el gato duerme",
      LimpiadorDeEco.limpiar("el gato duerme", "eh el gato duerme"),
    )
  }

  @Test
  fun `un eco sin marcador conocido tambien se corta`() {
    // La red que no depende de saberse el prompt de memoria: si mañana cambia el enunciado, la copia
    // literal de la frase objetivo sigue delatando el eco.
    assertEquals(
      "el gato duerme",
      LimpiadorDeEco.limpiar("el gato duerme", "el gato duerme el gato duerme"),
    )
  }

  @Test
  fun `una transcripcion vacia no revienta`() {
    assertEquals("", LimpiadorDeEco.limpiar("el gato duerme", ""))
    assertEquals("el gato", LimpiadorDeEco.limpiar("", "el gato"))
  }
}
