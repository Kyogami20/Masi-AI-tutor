package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La costura entre el CUENTISTA y la pantalla de Escuchar.
 *
 * Un cuento generado entra en la pantalla de Escuchar por la misma puerta que una página
 * fotografiada: `Lectura.de(texto, VersionTexto.CURADA)`. Toda la integración es esa línea, y por eso
 * conviene fijarla con un test — de lo contrario, romper el segmentador o cambiar `Lectura` haría
 * que un cuento se leyera a trozos incompletos y nadie se enteraría hasta tenerlo delante de un niño.
 *
 * El invariante es el mismo que fija [SegmentadorTest] para las páginas: **unir los fragmentos
 * reproduce el texto entero**. Se comprueba aquí otra vez, con textos generados, porque el cuento
 * llega por un camino distinto: sin las marcas `|` que pone el LECTOR, y con la puntuación que le
 * salga al modelo.
 */
class CuentoALecturaTest {

  /** Con la forma que devuelve el modelo: título, línea en blanco, cuento. */
  private val respuesta =
    "El perro perdido en el mercado" +
      System.lineSeparator() +
      System.lineSeparator() +
      "Rosa vivía cerca del mercado, en un pueblo de la sierra. Cada mañana su perro la " +
      "acompañaba a comprar pan. Un día el perro se perdió entre los puestos de fruta. " +
      "Rosa lo buscó por todas partes y no lo encontraba. Entonces oyó un ladrido detrás " +
      "de unas cajas. ¡Ahí estaba! El perro movía la cola muy contento. Rosa lo abrazó " +
      "fuerte y volvieron juntos a casa."

  private fun lecturaDelCuento(): Lectura {
    val crudo = FiltroDeCuento.partir(respuesta)
    val revision =
      FiltroDeCuento.revisar(crudo.titulo, crudo.texto, listOf("perro", "mercado"))
        as RevisionCuento.Aceptado
    return Lectura.de(revision.texto, VersionTexto.CURADA)
  }

  @Test
  fun `unir los fragmentos devuelve el cuento entero`() {
    // El invariante que garantiza que el niño lee el cuento COMPLETO y no media historia.
    val lectura = lecturaDelCuento()
    assertEquals(lectura.texto, lectura.fragmentos.joinToString(" "))
  }

  @Test
  fun `queda marcado como texto curado`() {
    // La costura: la pantalla de Escuchar no distingue entre esto y una página fotografiada, pero el
    // dato de dónde vino se conserva para cuando haga falta.
    assertEquals(VersionTexto.CURADA, lecturaDelCuento().version)
  }

  @Test
  fun `ningun fragmento se pasa del tope de grabacion`() {
    val largos =
      lecturaDelCuento().fragmentos.filter {
        Segmentador.cuentaPalabras(it) > Segmentador.MAX_PALABRAS
      }
    assertTrue("no caben en una grabación: $largos", largos.isEmpty())
  }

  @Test
  fun `el titulo no se cuela dentro de lo que el nino lee`() {
    // Si `partir` fallara, el título acabaría siendo la primera frase del cuento y el niño lo leería
    // en voz alta como si formara parte de la historia.
    val lectura = lecturaDelCuento()
    assertTrue("el título entró en el cuento", !lectura.texto.startsWith("El perro perdido"))
    assertTrue(lectura.texto.startsWith("Rosa vivía"))
  }

  @Test
  fun `las silabas cubren el cuento`() {
    val lectura = lecturaDelCuento()
    assertTrue("no se silabeó nada", lectura.silabas.isNotBlank())
    assertTrue("faltan guiones de sílaba", lectura.silabas.contains("-"))
  }

  @Test
  fun `un cuento sin puntuacion fuerte tambien se trocea`() {
    // El LECTOR marca las oraciones con `|`; el CUENTISTA no marca nada, así que el troceado depende
    // solo de la puntuación que le salga al modelo. Aquí se comprueba el peor caso.
    val corrido =
      "Rosa fue al mercado con su perro y compró pan y fruta y volvió despacio a casa " +
        "mientras el sol bajaba detrás de los cerros y el perro caminaba a su lado contento"
    val lectura = Lectura.de(corrido, VersionTexto.CURADA)
    assertEquals(lectura.texto, lectura.fragmentos.joinToString(" "))
    assertTrue("debía trocearse", lectura.fragmentos.size >= 2)
  }
}
