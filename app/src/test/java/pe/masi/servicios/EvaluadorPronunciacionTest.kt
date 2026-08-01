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
    assertEquals("perro", veredicto.error.esperado)
    // Las sílabas las resuelve el evaluador, no el modelo ni la pantalla.
    assertEquals("pe-rro", veredicto.silabas)
    assertTrue(veredicto.pista.isNotBlank())
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
