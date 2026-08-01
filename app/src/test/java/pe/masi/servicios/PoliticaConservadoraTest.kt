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
  fun `tres fallos en una frase de seis SI se marcan`() {
    // CAMBIO DE CRITERIO, y deliberado. El tope era un 2 fijo, así que esta lectura se descartaba
    // entera: cero tarjetas y un "no te escuché bien" que era falso.
    //
    // Un niño con dificultades falla tres palabras de seis con toda naturalidad —es el perfil de
    // usuario de esta app, no un caso raro— y tirar justo esas lecturas dejaba fuera precisamente
    // las que más falta hacía practicar.
    val r = PoliticaConservadora.evaluar("el perro corre por el campo", "el bero torre pol el campo")

    assertTrue(r.transcripcionFiable)
    assertEquals(3, r.errores.size)
  }

  @Test
  fun `pasada la mitad de la frase se sigue desconfiando`() {
    // El tope no desaparece, escala. Por encima de la mitad de las palabras ya no se distingue un
    // niño que lee muy mal de un transcriptor que no entendió nada, y marcar seis errores de golpe
    // es lo peor que puede hacer una app que enseña a leer.
    val r =
      PoliticaConservadora.evaluar("el perro corre por el campo", "un gato salta bajo la nieve")

    assertTrue(r.errores.isEmpty())
    assertFalse(r.transcripcionFiable)
    assertTrue(r.descartados.all { it.motivo == Motivo.DEMASIADAS_DISCREPANCIAS })
  }

  @Test
  fun `el tope nunca baja de tres`() {
    // En frases muy cortas, la mitad serían una o dos palabras y volveríamos al problema de antes.
    assertEquals(3, PoliticaConservadora.maxDiscrepancias(2))
    assertEquals(3, PoliticaConservadora.maxDiscrepancias(6))
    assertEquals(5, PoliticaConservadora.maxDiscrepancias(10))
  }

  @Test
  fun `una transcripcion desbordada no se cree`() {
    // La red bajo [LimpiadorDeEco]. Si llegan dieciséis palabras para una frase de cinco, no es el
    // niño hablando de más: es el modelo divagando, y ese texto de sobra le da al alineador un
    // camino más barato que el correcto, con lo que los errores de verdad se evaporan.
    val r =
      PoliticaConservadora.evaluar(
        "mi libro de historias biblicas",
        "mi cuaderno de historias biblicas y luego dijo otra cosa larguisima que no venia a cuento " +
          "mi libro de historias biblicas",
      )

    assertTrue(r.errores.isEmpty())
    assertFalse(r.transcripcionFiable)
    assertTrue("y con dudas: no se puede felicitar por esto", r.hayDudas)
    assertTrue(r.descartados.all { it.motivo == Motivo.TRANSCRIPCION_DESBORDADA })
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

  // --- "leyó bien" contra "no lo sé" ------------------------------------------------------------
  //
  // Esta distinción es la que sostiene todo el arreglo del ESCUCHA. Sin ella, `errores` vacío
  // significaba dos cosas opuestas —leyó bien, o descarté todo lo que vi— y aguas abajo se
  // interpretaba siempre como la primera.

  @Test
  fun `sustituir la palabra entera deja duda, no acierto`() {
    // EL CASO REPORTADO, tal cual ocurrió en el teléfono: el texto decía "libro" y se leyó
    // "cuaderno". Masi respondió "¡Muy bien!".
    val r = PoliticaConservadora.evaluar("el niño abrió el libro", "el niño abrió el cuaderno")

    assertTrue("sigue sin marcarse: una sola muestra no basta", r.errores.isEmpty())
    assertEquals(Motivo.DISTANCIA_EXCESIVA, r.descartados.single().motivo)
    assertTrue("descartar por distancia es duda, y la duda no se aplaude", r.hayDudas)
  }

  @Test
  fun `el acento andino NO deja duda`() {
    // La otra mitad, y pesa igual: un niño que dice "pilota" leyó bien. Mandarle repetir sería
    // castigarle el habla, que es justo el daño que este proyecto existe para no causar.
    val r = PoliticaConservadora.evaluar("la pelota roja", "la pilota roja")

    assertTrue(r.errores.isEmpty())
    assertEquals(Motivo.VARIACION_ANDINA, r.descartados.single().motivo)
    assertFalse("una variación de acento no es una duda", r.hayDudas)
  }

  @Test
  fun `una palabra corta tampoco deja duda`() {
    val r = PoliticaConservadora.evaluar("la casa de piedra", "la casa te piedra")
    assertFalse(r.hayDudas)
  }

  @Test
  fun `leer bien no deja ni errores ni dudas`() {
    val r = PoliticaConservadora.evaluar("la casa de piedra", "la casa de piedra")
    assertTrue(r.errores.isEmpty())
    assertFalse(r.hayDudas)
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
