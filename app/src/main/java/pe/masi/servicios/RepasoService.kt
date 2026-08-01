package pe.masi.servicios

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import pe.masi.datos.Tarjeta
import pe.masi.datos.TarjetaDao

/**
 * Repetición espaciada, versión simplificada de SM-2.
 *
 * Es una tabla de intervalos en vez de una multiplicación: más simple, más predecible e imposible
 * de romper. Detectar un error una vez no enseña nada; lo que enseña es volver, y volver cada vez
 * más tarde.
 */
object Repaso {

  /** Días de espera por nivel. Al acertar se sube un peldaño; al fallar se vuelve al primero. */
  val INTERVALOS = listOf(1, 2, 4, 8, 16, 32, 64)

  /** Máximo de tarjetas nuevas en una sesión. Un niño frustrado abandona. */
  const val MAX_NUEVAS_POR_SESION = 5

  /** Máximo de repasos en un día. Sesiones de 5 minutos, no de 30. */
  const val MAX_REPASOS_POR_DIA = 10

  fun repasar(tarjeta: Tarjeta, acierto: Boolean, hoy: LocalDate = LocalDate.now()): Tarjeta {
    val nuevoNivel = if (acierto) minOf(tarjeta.nivel + 1, INTERVALOS.size - 1) else 0
    return tarjeta.copy(
      nivel = nuevoNivel,
      proximoDiaEpoch = hoy.plusDays(INTERVALOS[nuevoNivel].toLong()).toEpochDay(),
      aciertos = if (acierto) tarjeta.aciertos + 1 else tarjeta.aciertos,
    )
  }

  /** Las que tocan hoy o ya deberían haber tocado. */
  fun pendientes(tarjetas: List<Tarjeta>, hoy: LocalDate = LocalDate.now()): List<Tarjeta> {
    val hoyEpoch = hoy.toEpochDay()
    return tarjetas.filter { it.proximoDiaEpoch <= hoyEpoch }
  }
}

/** La cara de [Repaso] que habla con la base de datos y aplica los topes de producto. */
class RepasoService(private val dao: TarjetaDao) {

  fun todas(): Flow<List<Tarjeta>> = dao.todas()

  fun cuentaPendientes(hoy: LocalDate = LocalDate.now()): Flow<Int> =
    dao.cuentaPendientes(hoy.toEpochDay())

  fun cuentaCreadasEstaSemana(hoy: LocalDate = LocalDate.now()): Flow<Int> =
    dao.cuentaCreadasDesde(hoy.minusDays(7).toEpochDay())

  /**
   * La cola de práctica: **todas** las tarjetas, con las que tocan hoy delante.
   *
   * El tope de [Repaso.MAX_REPASOS_POR_DIA] es una recomendación de ritmo, no una reja: se usa para
   * sugerir cuándo parar, no para vaciar la pantalla. Si un niño quiere seguir practicando, dejarle
   * seguir es siempre mejor que decirle que vuelva mañana.
   */
  suspend fun colaDePractica(hoy: LocalDate = LocalDate.now()): List<Tarjeta> =
    dao.ordenadasParaPracticar(hoy.toEpochDay())

  /** Cuántas de la cola tocaban hoy de verdad, para sugerir una parada natural. */
  fun cuantasTocanHoy(cola: List<Tarjeta>, hoy: LocalDate = LocalDate.now()): Int {
    val hoyEpoch = hoy.toEpochDay()
    return cola.count { it.proximoDiaEpoch <= hoyEpoch }
  }

  /** Quitar una palabra es un acto explícito del adulto, nunca algo que pase solo. */
  suspend fun olvidar(palabra: String) = dao.borrarPalabra(palabra)

  /**
   * Guarda una palabra fallada. Si ya existía, no la toca: perdería el nivel ganado.
   *
   * @return true si se creó una tarjeta nueva.
   */
  suspend fun guardarFallo(palabra: String, silabas: String, pista: String): Boolean {
    val existente = dao.buscar(palabra)
    if (existente != null) return false
    dao.insertar(Tarjeta(palabra = palabra, silabas = silabas, pista = pista))
    return true
  }

  /** Cuántas tarjetas nuevas caben todavía en esta sesión. */
  suspend fun huecoParaNuevas(creadasEnLaSesion: Int): Int =
    (Repaso.MAX_NUEVAS_POR_SESION - creadasEnLaSesion).coerceAtLeast(0)

  suspend fun registrarRepaso(tarjeta: Tarjeta, acierto: Boolean, hoy: LocalDate = LocalDate.now()) {
    dao.actualizar(Repaso.repasar(tarjeta, acierto, hoy))
  }

  /**
   * Sube de nivel la palabra que el niño acaba de lograr al reintentarla.
   *
   * La tarjeta **no** se borra: acertar una vez, en caliente y con la pista delante, no es lo mismo
   * que haberla aprendido. Lo que enseña es volver a encontrarla dentro de unos días.
   */
  suspend fun registrarAciertoDe(palabra: String, hoy: LocalDate = LocalDate.now()) {
    val tarjeta = dao.buscar(palabra) ?: return
    dao.actualizar(Repaso.repasar(tarjeta, acierto = true, hoy = hoy))
  }
}
