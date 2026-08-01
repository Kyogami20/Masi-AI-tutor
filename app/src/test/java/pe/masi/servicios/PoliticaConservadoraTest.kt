package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los tests que más importan del proyecto.
 *
 * La mitad de este archivo comprueba que Masi **no** corrige. Es deliberado: un falso positivo
 * —decirle a un niño que se equivocó cuando no lo hizo— hace más daño que dejar pasar diez errores
 * reales. Si alguna vez hay que elegir entre pasar `DetectorErroresTest` y pasar este, gana este.
 */
class PoliticaConservadoraTest {

  // --- lo que SÍ se marca ----------------------------------------------------------------------

  @Test
  fun `marca el error de la demo`() {
    val r = PoliticaConservadora.evaluar("El perro corre por el campo", "El bero corre por el campo")
    assertTrue(r.transcripcionFiable)
    assertEquals(1, r.errores.size)
    assertEquals("perro", r.errores[0].esperado)
    assertEquals(TipoError.SUSTITUCION_INICIAL, r.errores[0].tipo)
  }

  @Test
  fun `marca la inversion aunque la palabra sea corta`() {
    // Las mismas letras en otro orden no salen de una transcripción torcida: es un error real.
    val r = PoliticaConservadora.evaluar("el sol es grande", "le sol es grande")
    assertEquals(1, r.errores.size)
    assertEquals(TipoError.INVERSION, r.errores[0].tipo)
  }

  @Test
  fun `marca la omision de una palabra larga`() {
    val r = PoliticaConservadora.evaluar("el murcielago come fruta", "el come fruta")
    assertEquals(1, r.errores.size)
    assertEquals(TipoError.OMISION, r.errores[0].tipo)
    assertEquals("murcielago", r.errores[0].esperado)
  }

  // --- lo que NO se marca ----------------------------------------------------------------------

  @Test
  fun `no marca nada cuando la lectura es correcta`() {
    val r = PoliticaConservadora.evaluar("Mi mamá me mima", "mi mama me mima")
    assertTrue(r.errores.isEmpty())
    assertTrue(r.transcripcionFiable)
  }

  @Test
  fun `no marca nada si no hubo transcripcion`() {
    val r = PoliticaConservadora.evaluar("El perro corre", "")
    assertTrue(r.errores.isEmpty())
    assertFalse(r.transcripcionFiable)
  }

  @Test
  fun `no marca nada si el nino se detuvo a mitad`() {
    val r = PoliticaConservadora.evaluar("el perro corre por el campo verde", "el perro")
    assertTrue(r.errores.isEmpty())
    assertFalse(r.transcripcionFiable)
    assertTrue(r.descartados.all { it.motivo == Motivo.LECTURA_INCOMPLETA })
  }

  @Test
  fun `no marca nada cuando hay demasiadas discrepancias`() {
    // Tres o más diferencias significan que quien falló es el transcriptor, no el niño.
    val r = PoliticaConservadora.evaluar("el perro corre por el campo", "el bero torre pol el campo")
    assertTrue(r.errores.isEmpty())
    assertFalse(r.transcripcionFiable)
    assertTrue(r.descartados.all { it.motivo == Motivo.DEMASIADAS_DISCREPANCIAS })
  }

  @Test
  fun `no marca la variacion vocalica andina`() {
    // "pilota" por "pelota" es el acento del niño, no un error de lectura. Marcarlo sería
    // exactamente el daño que este proyecto dice combatir.
    val r = PoliticaConservadora.evaluar("la pelota es roja", "la pilota es roja")
    assertTrue(r.errores.isEmpty())
    assertEquals(1, r.descartados.size)
    assertEquals(Motivo.VARIACION_ANDINA, r.descartados[0].motivo)
  }

  @Test
  fun `no marca la variacion vocalica o por u`() {
    val r = PoliticaConservadora.evaluar("mira el conejo", "mira el cunejo")
    assertTrue(r.errores.isEmpty())
    assertEquals(Motivo.VARIACION_ANDINA, r.descartados[0].motivo)
  }

  @Test
  fun `no marca cuando lo transcrito no se parece en nada`() {
    val r = PoliticaConservadora.evaluar("el elefante camina", "el xkjhgfd camina")
    assertTrue(r.errores.isEmpty())
    assertEquals(Motivo.DISTANCIA_EXCESIVA, r.descartados[0].motivo)
  }

  @Test
  fun `no marca sustituciones en palabras muy cortas`() {
    // "de" leído "te" puede ser perfectamente ruido de transcripción.
    val r = PoliticaConservadora.evaluar("la casa de piedra", "la casa te piedra")
    assertTrue(r.errores.isEmpty())
    assertEquals(Motivo.PALABRA_MUY_CORTA, r.descartados[0].motivo)
  }

  // --- funciones auxiliares --------------------------------------------------------------------

  @Test
  fun `esVariacionAndina distingue acento de error`() {
    assertTrue(PoliticaConservadora.esVariacionAndina("pelota", "pilota"))
    assertTrue(PoliticaConservadora.esVariacionAndina("conejo", "cunejo"))
    // "bero" por "perro" no se explica por vocales: es un error de lectura de verdad.
    assertFalse(PoliticaConservadora.esVariacionAndina("perro", "bero"))
    // Palabras idénticas no son "variación": simplemente no hay nada que descartar.
    assertFalse(PoliticaConservadora.esVariacionAndina("casa", "casa"))
  }

  @Test
  fun `distanciaAceptable acepta el error tipico y rechaza el disparate`() {
    assertTrue(PoliticaConservadora.distanciaAceptable("perro", "bero"))
    assertTrue(PoliticaConservadora.distanciaAceptable("dedo", "bebo"))
    assertFalse(PoliticaConservadora.distanciaAceptable("elefante", "xkjhgfd"))
  }
}
