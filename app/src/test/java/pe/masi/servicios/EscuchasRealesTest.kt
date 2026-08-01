package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Las ocho escuchas del Redmi que destaparon el fallo, replicadas tal cual.
 *
 * Copiadas del log del 1 de agosto de 2026, sin retocar. En el aparato, las tres primeras y la
 * quinta se saldaron con un "¡Muy bien!" mientras el niño leía "cuaderno" donde decía "libro".
 *
 * Este archivo existe porque el fallo no se veía en ningún test: cada pieza —el detector, la
 * política, el evaluador— hacía exactamente lo que decía su contrato, y aun así el conjunto
 * felicitaba por una sustitución de palabra entera. Solo se ve juntando las cuatro con una entrada
 * de verdad.
 */
class EscuchasRealesTest {

  /** Lo que Masi habría contestado, sin cargar el modelo. */
  private fun veredictoDe(esperado: String, transcrito: String): String {
    val limpio = LimpiadorDeEco.limpiar(esperado, transcrito)
    val r = PoliticaConservadora.evaluar(esperado, limpio)
    return when {
      r.errores.isNotEmpty() -> "marca " + r.errores.joinToString(",") { it.esperado + "→" + it.dicho }
      r.hayDudas -> "repite"
      else -> "muy bien"
    }
  }

  @Test
  fun `las cuatro escuchas con eco dejan de ser un aplauso`() {
    // El corazón de la regresión. Ninguna de estas cuatro puede volver a salir "muy bien".
    assertFalse(
      veredictoDe(
        "MI LIBRO DE HISTORIAS BÍBLICAS",
        "mi cuaderno de historias bíblicas el niño debía leer exactamente esto " +
          "mi libro de historias bíblicas",
      ) == "muy bien"
    )
    assertFalse(
      veredictoDe(
        "Este es un libro de historias reales.",
        "este es un libro de historias peales el niño debía leer exactamente esto " +
          "este es un libro de historias reales",
      ) == "muy bien"
    )
    assertFalse(
      veredictoDe(
        "más grandioso del mundo, la Biblia.",
        "más grandioso del pundo la biblia el niño debía leer exactamente esto " +
          "más grandioso del mundo la biblia",
      ) == "muy bien"
    )
    assertFalse(
      veredictoDe(
        "Se han tomado del libro",
        "Se han tomado del kibroEl niño debía leer exactamente esto: \"Se han tomado del libro\"",
      ) == "muy bien"
    )
  }

  @Test
  fun `el eco recortado devuelve la palabra a su tarjeta`() {
    // Y no basta con dejar de aplaudir: la palabra mal leída tiene que llegar a ser tarjeta, que era
    // la otra mitad de lo reportado. Aquí el recorte cae dentro de "kibroEl" y deja "kibro" entero.
    assertEquals(
      "marca libro→kibro",
      veredictoDe(
        "Se han tomado del libro",
        "Se han tomado del kibroEl niño debía leer exactamente esto: \"Se han tomado del libro\"",
      ),
    )
  }

  @Test
  fun `tres fallos en una frase ya no tiran la lectura entera`() {
    // Con el tope viejo de 2 discrepancias fijas, esta lectura se descartaba completa. Es justo el
    // caso de un niño con dificultades, y también el de una demostración con errores preparados.
    assertEquals(
      "marca grandioso→prandioso,mundo→rundo,biblia→piblia",
      veredictoDe("más grandioso del mundo, la Biblia.", "más prandioso del rundo la piblia"),
    )
  }

  @Test
  fun `las escuchas limpias del log siguen comportandose igual`() {
    // Cuatro de las ocho salieron sin eco. Ninguna puede cambiar de veredicto por este arreglo.
    assertEquals(
      "muy bien",
      veredictoDe(
        "Los relatos te dan una historia del mundo desde",
        "los relatos te dan una historia del mundo desde",
      ),
    )
    assertEquals(
      "marca libro→pibro,historias→ristorias",
      veredictoDe("ESTE es un libro de historias reales.", "este es un pibro de ristorias reales"),
    )
    assertEquals(
      "marca mundo→rundo",
      veredictoDe("más grandioso del mundo, la Biblia.", "más grandioso del rundo la biblia"),
    )
  }
}
