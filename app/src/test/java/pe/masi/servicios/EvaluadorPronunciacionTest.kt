package pe.masi.servicios

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El evaluador es corto, pero decide lo más delicado de la app: cuándo se le dice a un niño que
 * algo salió distinto.
 *
 * La rama que más importa es la segunda: si la transcripción no es de fiar, **no se marca error**.
 * Ese es el compromiso ético del proyecto —dejar pasar un fallo real antes que inventar uno— y
 * tiene que estar sujeto por un test, no por la buena memoria de quien toque esto dentro de un mes.
 */
class EvaluadorPronunciacionTest {

  private val audio = ByteArray(64) { 1 }

  private fun evaluador(
    resultado: ResultadoEscucha,
    pista: String = "¡Casi! Dice pe-rro, con la p de pan.",
  ) =
    EvaluadorPronunciacion(
      escucha = { _, _, _ -> resultado },
      tutor = { Pista(pista, deRepuesto = false) },
    )

  private fun errorDePerro() =
    ErrorLectura(indice = 1, esperado = "perro", dicho = "bero", tipo = TipoError.SUSTITUCION_INICIAL)

  @Test
  fun `leer bien da acierto`() = runBlocking {
    val resultado =
      ResultadoEscucha(
        transcripcion = "el perro corre",
        lectura = ResultadoLectura(emptyList(), emptyList(), transcripcionFiable = true),
      )
    assertEquals(Veredicto.Acierto, evaluador(resultado).evaluar("el perro corre", audio))
  }

  @Test
  fun `una transcripcion poco fiable nunca se convierte en error`() = runBlocking {
    // Con transcripcionFiable = false da igual lo que digan los errores: no se marca ninguno.
    val resultado =
      ResultadoEscucha(
        transcripcion = "ruido",
        lectura =
          ResultadoLectura(
            errores = listOf(errorDePerro()),
            descartados = emptyList(),
            transcripcionFiable = false,
          ),
      )
    assertEquals(Veredicto.NoSeEntendio, evaluador(resultado).evaluar("el perro corre", audio))
  }

  // --- El fallo del "¡Muy bien!" ----------------------------------------------------------------

  @Test
  fun `sustituir la palabra entera NO se premia`() = runBlocking {
    // EL CASO REPORTADO. En el teléfono: el texto decía "libro", se leyó "cuaderno", y Masi
    // respondió "¡Muy bien!" sin crear ninguna tarjeta.
    //
    // La causa no era el umbral ni el prompt: era que este evaluador devolvía `Acierto` en cuanto
    // la lista de errores venía vacía, sin mirar si estaba vacía porque el niño leyó bien o porque
    // se había descartado todo por dudoso. Son cosas opuestas.
    val resultado =
      ResultadoEscucha(
        transcripcion = "el niño abrió el cuaderno",
        lectura = PoliticaConservadora.evaluar("el niño abrió el libro", "el niño abrió el cuaderno"),
      )

    val veredicto = evaluador(resultado).evaluar("el niño abrió el libro", audio)
    assertEquals(Veredicto.NoSeEntendio, veredicto)
  }

  @Test
  fun `el acento andino sigue siendo un acierto`() = runBlocking {
    // La contrapartida, y pesa igual que lo anterior: arreglar el falso "muy bien" no puede
    // convertir a Masi en una app que le manda repetir a un niño por hablar como habla.
    val resultado =
      ResultadoEscucha(
        transcripcion = "la pilota roja",
        lectura = PoliticaConservadora.evaluar("la pelota roja", "la pilota roja"),
      )

    assertEquals(Veredicto.Acierto, evaluador(resultado).evaluar("la pelota roja", audio))
  }

  @Test
  fun `repetir la misma sustitucion la convierte en tarjeta`() = runBlocking {
    // La otra mitad de lo reportado: con solo dejar de felicitar, "libro→cuaderno" nunca llegaría a
    // practicarse, y sería justo la palabra que peor lee la única que nunca aparece en Practicar.
    //
    // Dos grabaciones independientes que dan exactamente el mismo resultado ya no son ruido.
    val resultado =
      ResultadoEscucha(
        transcripcion = "el niño abrió el cuaderno",
        lectura = PoliticaConservadora.evaluar("el niño abrió el libro", "el niño abrió el cuaderno"),
      )
    val evaluador = evaluador(resultado)

    assertEquals(Veredicto.NoSeEntendio, evaluador.evaluar("el niño abrió el libro", audio))

    val segundo = evaluador.evaluar("el niño abrió el libro", audio)
    assertTrue("la segunda vez ya es evidencia", segundo is Veredicto.Falla)
    val palabra = (segundo as Veredicto.Falla).palabras.single()
    assertEquals("libro", palabra.escritura)
    assertEquals("cuaderno", palabra.error.dicho)
  }

  @Test
  fun `leer bien dos veces seguidas no inventa nada`() = runBlocking {
    // La prueba de que la memoria de dudas no se contamina sola: sin descartes que recordar, la
    // segunda lectura correcta sigue siendo un acierto.
    val resultado =
      ResultadoEscucha(
        transcripcion = "el perro corre",
        lectura = ResultadoLectura(emptyList(), emptyList(), transcripcionFiable = true),
      )
    val evaluador = evaluador(resultado)

    assertEquals(Veredicto.Acierto, evaluador.evaluar("el perro corre", audio))
    assertEquals(Veredicto.Acierto, evaluador.evaluar("el perro corre", audio))
  }

  @Test
  fun `guarda todas las palabras falladas, no solo la primera`() = runBlocking {
    // El fallo que motivó este test: el evaluador hacía `errores.firstOrNull()` y la segunda
    // palabra de la oración no llegaba a ser tarjeta nunca.
    val resultado =
      ResultadoEscucha(
        transcripcion = "el bero core",
        lectura =
          ResultadoLectura(
            errores =
              listOf(
                errorDePerro(),
                ErrorLectura(2, "corre", "core", TipoError.OMISION_PARCIAL),
              ),
            descartados = emptyList(),
            transcripcionFiable = true,
          ),
      )
    val veredicto = evaluador(resultado).evaluar("el perro corre", audio)
    veredicto as Veredicto.Falla
    assertEquals(listOf("perro", "corre"), veredicto.palabras.map { it.escritura })
  }

  @Test
  fun `la tarjeta guarda la palabra con su tilde, no la forma de comparar`() = runBlocking {
    // El detector compara sin tildes a propósito. Lo que no puede pasar es que esa forma llegue a
    // una tarjeta: enseñaría la ortografía mal al niño que está aprendiendo a escribirla.
    val resultado =
      ResultadoEscucha(
        transcripcion = "se estableshio",
        lectura =
          ResultadoLectura(
            errores =
              listOf(ErrorLectura(2, "establecio", "estableshio", TipoError.SUSTITUCION_INICIAL)),
            descartados = emptyList(),
            transcripcionFiable = true,
          ),
      )
    val veredicto = evaluador(resultado).evaluar("se estableció en Baltimore", audio)
    veredicto as Veredicto.Falla
    assertEquals("estableció", veredicto.palabras.single().escritura)
    // Y la clave, que es lo que deduplica en la base, sigue siendo la forma normalizada.
    assertEquals("establecio", veredicto.palabras.single().clave)
  }

  @Test
  fun `un error fiable trae la pista y las silabas ya resueltas`() = runBlocking {
    val resultado =
      ResultadoEscucha(
        transcripcion = "el bero corre",
        lectura =
          ResultadoLectura(
            errores = listOf(errorDePerro()),
            descartados = emptyList(),
            transcripcionFiable = true,
          ),
      )
    val veredicto = evaluador(resultado).evaluar("el perro corre", audio)
    assertTrue("debía ser una falla, y fue $veredicto", veredicto is Veredicto.Falla)
    veredicto as Veredicto.Falla
    val fallada = veredicto.palabras.single()
    assertEquals("perro", fallada.escritura)
    // Las sílabas las resuelve el evaluador, no el modelo ni la pantalla.
    assertEquals("pe-rro", fallada.silabas)
    assertTrue(fallada.pista.isNotBlank())
  }

  @Test
  fun `la tarjeta conserva la tilde por el camino completo`() = runBlocking {
    // REGRESIÓN. La versión anterior buscaba la palabra por `indice - 1` suponiendo que el índice
    // era 1-based. No lo era: la guarda detectaba que no cuadraba, caía al respaldo y la tarjeta
    // salía SIN TILDE. No petaba nada, así que la app estuvo enseñando "mama" y "pesame" durante
    // varias versiones. Por eso ahora se empareja por forma normalizada y no por índice.
    val fragmento = "Mi mamá compra pan en el mercado."
    val dicho = "mi nama compra pan en el mercado"
    val lectura = PoliticaConservadora.evaluar(fragmento, dicho)

    val evaluador =
      EvaluadorPronunciacion(
        escucha = { _, _, _ -> ResultadoEscucha(dicho, lectura) },
        tutor = { Pista("pista", deRepuesto = false) },
      )
    val veredicto = evaluador.evaluar(fragmento, audio) as Veredicto.Falla
    val fallada = veredicto.palabras.single()

    assertEquals("mamá", fallada.escritura)
    // Y la clave sigue siendo la forma sin tilde, que es la que deduplica en la base.
    assertEquals("mama", fallada.clave)
    // Las sílabas se calculan sobre la escritura real, no sobre la clave.
    assertEquals("ma-má", fallada.silabas)
  }

  @Test
  fun `un audio vacio no llega ni a molestar al modelo`() = runBlocking {
    val evaluador =
      EvaluadorPronunciacion(
        escucha = { _, _, _ -> error("no debería llamarse con un audio vacío") },
        tutor = { error("no debería llamarse") },
      )
    assertEquals(Veredicto.NoSeEntendio, evaluador.evaluar("el perro corre", ByteArray(0)))
  }
}
