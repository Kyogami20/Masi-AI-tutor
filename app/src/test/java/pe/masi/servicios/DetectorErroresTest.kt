package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorErroresTest {

  // --- normalizar ------------------------------------------------------------------------------

  @Test
  fun `normalizar quita tildes, signos y mayusculas`() {
    assertEquals("el perro corre", DetectorErrores.normalizar("¡El PERRO corre!"))
    assertEquals("mama compro pan", DetectorErrores.normalizar("Mamá compró pan."))
  }

  @Test
  fun `normalizar conserva la enie`() {
    // Si esto falla, "años" se convierte en "anos": otra palabra, y errores inventados.
    assertEquals("el niño tiene cinco años", DetectorErrores.normalizar("El niño tiene cinco AÑOS"))
  }

  @Test
  fun `normalizar colapsa espacios y recorta`() {
    assertEquals("la casa", DetectorErrores.normalizar("   la    casa \n"))
  }

  @Test
  fun `palabras devuelve lista vacia con texto vacio`() {
    assertEquals(emptyList<String>(), DetectorErrores.palabras("   "))
    assertEquals(emptyList<String>(), DetectorErrores.palabras("!!!"))
  }

  // --- alinear ---------------------------------------------------------------------------------

  @Test
  fun `alinear detecta una sustitucion en medio`() {
    val pares = DetectorErrores.alinear(listOf("el", "perro", "corre"), listOf("el", "bero", "corre"))
    assertEquals(3, pares.size)
    assertEquals(Operacion.IGUAL, pares[0].operacion)
    assertEquals(Operacion.SUSTITUCION, pares[1].operacion)
    assertEquals("perro", pares[1].esperado)
    assertEquals("bero", pares[1].dicho)
    assertEquals(Operacion.IGUAL, pares[2].operacion)
  }

  @Test
  fun `alinear detecta una omision`() {
    val pares = DetectorErrores.alinear(listOf("el", "perro", "corre"), listOf("el", "corre"))
    assertEquals(1, pares.count { it.operacion == Operacion.OMISION })
    assertEquals("perro", pares.first { it.operacion == Operacion.OMISION }.esperado)
  }

  @Test
  fun `alinear detecta una insercion`() {
    val pares = DetectorErrores.alinear(listOf("el", "perro"), listOf("el", "gran", "perro"))
    assertEquals(1, pares.count { it.operacion == Operacion.INSERCION })
    assertEquals("gran", pares.first { it.operacion == Operacion.INSERCION }.dicho)
  }

  @Test
  fun `alinear con listas identicas no produce operaciones`() {
    val palabras = listOf("la", "casa", "es", "grande")
    val pares = DetectorErrores.alinear(palabras, palabras)
    assertTrue(pares.all { it.operacion == Operacion.IGUAL })
  }

  @Test
  fun `alinear con dicho vacio marca todo como omision`() {
    val pares = DetectorErrores.alinear(listOf("el", "perro"), emptyList())
    assertEquals(2, pares.size)
    assertTrue(pares.all { it.operacion == Operacion.OMISION })
  }

  @Test
  fun `alinear con esperado vacio marca todo como insercion`() {
    val pares = DetectorErrores.alinear(emptyList(), listOf("el", "perro"))
    assertEquals(2, pares.size)
    assertTrue(pares.all { it.operacion == Operacion.INSERCION })
  }

  @Test
  fun `los indices de la alineacion apuntan a la palabra esperada`() {
    val pares =
      DetectorErrores.alinear(listOf("mi", "mama", "me", "mima"), listOf("mi", "nana", "me", "mima"))
    val sustitucion = pares.first { it.operacion == Operacion.SUSTITUCION }
    assertEquals(1, sustitucion.indice)
  }

  // --- clasificar ------------------------------------------------------------------------------

  @Test
  fun `clasificar reconoce la sustitucion inicial (el caso b-d-p)`() {
    assertEquals(TipoError.SUSTITUCION_INICIAL, DetectorErrores.clasificar("dedo", "bedo"))
    assertEquals(TipoError.SUSTITUCION_INICIAL, DetectorErrores.clasificar("pelota", "belota"))
  }

  @Test
  fun `clasificar reconoce la inversion aunque cambie la primera letra`() {
    // La inversión gana a la sustitución inicial porque es más específica: si son las mismas
    // letras en otro orden, es una inversión, y el TUTOR debe dar la pista de inversión.
    assertEquals(TipoError.INVERSION, DetectorErrores.clasificar("el", "le"))
    assertEquals(TipoError.INVERSION, DetectorErrores.clasificar("pardo", "prado"))
  }

  @Test
  fun `clasificar reconoce la omision total`() {
    assertEquals(TipoError.OMISION, DetectorErrores.clasificar("murcielago", ""))
  }

  @Test
  fun `clasificar reconoce la omision parcial`() {
    // "plato" leído "pato": se comió la l, misma inicial, más corta.
    assertEquals(TipoError.OMISION_PARCIAL, DetectorErrores.clasificar("plato", "pato"))
  }

  @Test
  fun `clasificar cae en sustitucion generica`() {
    assertEquals(TipoError.SUSTITUCION, DetectorErrores.clasificar("casa", "casita"))
  }

  @Test
  fun `clasificar ignora tildes y mayusculas`() {
    assertEquals(TipoError.SUSTITUCION_INICIAL, DetectorErrores.clasificar("Árbol", "carbol"))
  }

  // --- distancia -------------------------------------------------------------------------------

  @Test
  fun `distancia de edicion basica`() {
    assertEquals(0, DetectorErrores.distancia("perro", "perro"))
    assertEquals(2, DetectorErrores.distancia("perro", "bero"))
    assertEquals(4, DetectorErrores.distancia("", "casa"))
    assertEquals(1, DetectorErrores.distancia("plato", "pato"))
  }

  // --- comparar --------------------------------------------------------------------------------

  @Test
  fun `comparar encuentra el error del caso de la demo`() {
    val errores = DetectorErrores.comparar("El perro corre por el campo", "El bero corre por el campo")
    assertEquals(1, errores.size)
    assertEquals("perro", errores[0].esperado)
    assertEquals("bero", errores[0].dicho)
    assertEquals(TipoError.SUSTITUCION_INICIAL, errores[0].tipo)
  }

  @Test
  fun `comparar no marca nada cuando la lectura es correcta`() {
    val errores = DetectorErrores.comparar("Mi mamá me mima", "mi mama me mima")
    assertTrue(errores.isEmpty())
  }

  @Test
  fun `comparar ignora las palabras dichas de mas`() {
    // Muletillas y repeticiones del niño no son errores de lectura.
    val errores = DetectorErrores.comparar("El sol brilla", "el este sol brilla")
    assertTrue(errores.isEmpty())
  }

  @Test
  fun `comparar devuelve vacio si no habia nada que leer`() {
    assertTrue(DetectorErrores.comparar("", "algo").isEmpty())
  }
}
