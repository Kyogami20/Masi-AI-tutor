package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La búsqueda en el banco, con un índice inventado.
 *
 * Lo que fija este archivo es que la normalización de Kotlin y la de Python coinciden. El índice se
 * genera desde `herramientas/descargar_pictogramas.py` con las mismas reglas —minúsculas, sin
 * tildes, ñ intacta—, y si las dos se separan, las búsquedas fallan **en silencio**: no hay
 * excepción, simplemente ninguna tarjeta tiene dibujo y nadie sabe por qué.
 */
class BancoDePictogramasTest {

  private val banco =
    BancoDePictogramas(
      mapOf(
        "perro" to "perro.webp",
        "montaña" to "montaña.webp",
        "arbol" to "arbol.webp",
        "construir" to "construir.webp",
        "campo" to "campo.webp",
        "niño" to "niño.webp",
      )
    )

  @Test
  fun `encuentra una palabra exacta`() {
    assertEquals("perro.webp", banco.buscar("perro"))
  }

  @Test
  fun `las mayusculas no importan`() {
    assertEquals("perro.webp", banco.buscar("Perro"))
    assertEquals("perro.webp", banco.buscar("PERRO"))
  }

  @Test
  fun `la tilde se quita igual que en el indice`() {
    // El índice guarda "arbol" sin tilde, porque así lo normalizó el script de Python.
    assertEquals("arbol.webp", banco.buscar("árbol"))
  }

  @Test
  fun `la enie se conserva`() {
    // "montaña" y "niño" mantienen la ñ a los dos lados. Si alguno la convirtiera en n, esto falla.
    assertEquals("montaña.webp", banco.buscar("montaña"))
    assertEquals("niño.webp", banco.buscar("niño"))
  }

  @Test
  fun `encuentra el singular cuando el modelo pide el plural`() {
    // Un modelo pide "perros" o "montañas" con toda naturalidad; duplicar el banco sería absurdo.
    assertEquals("perro.webp", banco.buscar("perros"))
    assertEquals("arbol.webp", banco.buscar("árboles"))
  }

  @Test
  fun `saca la palabra util de una frase`() {
    assertEquals("perro.webp", banco.buscar("el perro"))
    assertEquals("campo.webp", banco.buscar("un campo grande"))
  }

  @Test
  fun `devuelve null cuando de verdad no esta`() {
    // Este es el caso que da sentido a la herramienta: "estableció" no tiene pictograma y no lo
    // tendrá nunca. Aquí el código se rinde; el modelo, en cambio, prueba con "construir".
    assertNull(banco.buscar("estableció"))
    assertNotNullDespues("construir")
  }

  private fun assertNotNullDespues(concepto: String) {
    assertEquals("construir.webp", banco.buscar(concepto))
  }

  @Test
  fun `encuentra la palabra derivada de una que si esta`() {
    // Medido en el teléfono: para "pésame" el modelo buscó "tristeza", y el banco tiene "triste".
    val conRaices =
      BancoDePictogramas(mapOf("triste" to "triste.webp", "cansado" to "cansado.webp"))
    assertEquals("triste.webp", conRaices.buscar("tristeza"))
    assertEquals("cansado.webp", conRaices.buscar("cansados"))
  }

  @Test
  fun `no empareja palabras que solo comparten el principio`() {
    val corto = BancoDePictogramas(mapOf("casa" to "casa.webp", "campo" to "campo.webp"))
    // "casa" tiene 4 letras: por debajo del mínimo de raíz, así que no arrastra a "casi".
    assertNull(corto.buscar("casi"))
    // Y "campo" no debe capturar "campana" pese a compartir cinco letras... comparte solo "camp".
    assertNull(corto.buscar("campana"))
  }

  @Test
  fun `un concepto vacio no revienta`() {
    assertNull(banco.buscar(""))
    assertNull(banco.buscar("   "))
    assertNull(banco.buscar("!!"))
  }

  @Test
  fun `la busqueda directa resuelve sin necesitar al modelo`() {
    // El criterio de reparto entre código y modelo: si el código puede, el código lo hace. Estas
    // cuatro se resuelven en microsegundos y no deben gastar una llamada al modelo.
    for (c in listOf("perro", "Perro", "perros", "el perro")) {
      assertEquals("resuelto en código: $c", "perro.webp", banco.buscar(c))
    }
    // Y esta no. Aquí sí se justifica llamar al modelo, porque el código ya no puede hacer nada.
    assertNull(banco.buscar("estableció"))
  }

  @Test
  fun `un banco vacio no encuentra nada y no falla`() {
    assertEquals(0, BancoDePictogramas.VACIO.cuantos)
    assertNull(BancoDePictogramas.VACIO.buscar("perro"))
    assertFalse(BancoDePictogramas.VACIO.tiene("perro"))
  }

  @Test
  fun `tiene responde lo mismo que buscar`() {
    assertTrue(banco.tiene("perro"))
    assertFalse(banco.tiene("estableció"))
  }

  @Test
  fun `la ruta apunta dentro de assets`() {
    assertEquals("pictogramas/perro.webp", BancoDePictogramas.rutaDe("perro.webp"))
  }
}
