package pe.masi.servicios

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.masi.datos.Tarjeta

class RepasoTest {

  private val hoy = LocalDate.of(2026, 7, 30)

  private fun tarjeta(nivel: Int = 0, aciertos: Int = 0) =
    Tarjeta(
      palabra = "perro",
      silabas = "pe-rro",
      pista = "La p tiene barriguita",
      nivel = nivel,
      proximoDiaEpoch = hoy.toEpochDay(),
      creadaDiaEpoch = hoy.toEpochDay(),
      aciertos = aciertos,
    )

  @Test
  fun `acertar sube un nivel y aleja la proxima fecha`() {
    val t = Repaso.repasar(tarjeta(nivel = 0), acierto = true, hoy = hoy)
    assertEquals(1, t.nivel)
    assertEquals(hoy.plusDays(2), t.proxima) // INTERVALOS[1] == 2
    assertEquals(1, t.aciertos)
  }

  @Test
  fun `fallar devuelve al nivel cero y a manana`() {
    val t = Repaso.repasar(tarjeta(nivel = 4, aciertos = 4), acierto = false, hoy = hoy)
    assertEquals(0, t.nivel)
    assertEquals(hoy.plusDays(1), t.proxima) // INTERVALOS[0] == 1
    assertEquals(4, t.aciertos) // fallar no borra el historial de aciertos
  }

  @Test
  fun `el nivel no se sale de la tabla`() {
    val ultimo = Repaso.INTERVALOS.size - 1
    val t = Repaso.repasar(tarjeta(nivel = ultimo), acierto = true, hoy = hoy)
    assertEquals(ultimo, t.nivel)
    assertEquals(hoy.plusDays(64), t.proxima)
  }

  @Test
  fun `la escalera completa llega a 64 dias`() {
    var t = tarjeta(nivel = 0)
    val esperados = listOf(2, 4, 8, 16, 32, 64, 64)
    for (dias in esperados) {
      t = Repaso.repasar(t, acierto = true, hoy = hoy)
      assertEquals(hoy.plusDays(dias.toLong()), t.proxima)
    }
  }

  @Test
  fun `pendientes incluye las de hoy y las atrasadas, no las futuras`() {
    val atrasada = tarjeta().copy(palabra = "ayer", proximoDiaEpoch = hoy.minusDays(3).toEpochDay())
    val deHoy = tarjeta().copy(palabra = "hoy", proximoDiaEpoch = hoy.toEpochDay())
    val futura = tarjeta().copy(palabra = "manana", proximoDiaEpoch = hoy.plusDays(1).toEpochDay())

    val pendientes = Repaso.pendientes(listOf(atrasada, deHoy, futura), hoy)

    assertEquals(2, pendientes.size)
    assertTrue(pendientes.any { it.palabra == "ayer" })
    assertTrue(pendientes.any { it.palabra == "hoy" })
    assertFalse(pendientes.any { it.palabra == "manana" })
  }

  @Test
  fun `los topes de producto son los del documento`() {
    assertEquals(5, Repaso.MAX_NUEVAS_POR_SESION)
    assertEquals(10, Repaso.MAX_REPASOS_POR_DIA)
  }
}
