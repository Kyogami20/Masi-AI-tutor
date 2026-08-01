package pe.masi.servicios

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import pe.masi.datos.Cuento
import pe.masi.datos.CuentoDao
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
class RepasoService(private val dao: TarjetaDao, private val cuentos: CuentoDao) {

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
  /**
   * Guarda las palabras falladas de una oración, en orden, hasta agotar el cupo de la sesión.
   *
   * Es una lista y no una palabra porque en una oración de diez palabras se puede fallar más de
   * una, y antes solo se guardaba la primera.
   *
   * El cupo ([Repaso.MAX_NUEVAS_POR_SESION]) se aplica **por tarjeta creada, no por oración**, y
   * ahora importa más que antes: con varias palabras por frase se llenaría en dos o tres oraciones.
   * Las que no caben no se pierden como problema —se le siguen mostrando al niño con su pista—,
   * simplemente no entran hoy en la cola de repaso. Un niño con veinte tarjetas nuevas abandona.
   */
  suspend fun guardarFallos(palabras: List<PalabraFallada>, creadasEnLaSesion: Int): List<Guardado> {
    var hueco = huecoParaNuevas(creadasEnLaSesion)
    return palabras.map { palabra ->
      if (hueco <= 0 && dao.buscar(palabra.clave) == null) {
        Guardado.NO_CUPO
      } else {
        guardarUna(palabra).also { if (it == Guardado.CREADA) hueco-- }
      }
    }
  }

  private suspend fun guardarUna(palabra: PalabraFallada): Guardado {
    val existente = dao.buscar(palabra.clave)
    if (existente != null) {
      // El nivel NO se toca: volver a fallar una palabra que llevas practicando dos semanas no debe
      // devolverte al principio de la escalera.
      //
      // Pero la ortografía sí se repara si hace falta. Las tarjetas creadas antes de que se guardara
      // la escritura real dicen "mama" y "pesame", y esas tildes no se pueden adivinar — "mama" y
      // "mamá" son palabras distintas. Aquí, en cambio, no se adivina nada: la palabra acaba de
      // aparecer en un texto de verdad y sabemos cómo está impresa. Solo se corrige con esa prueba
      // delante.
      if (existente.escritura != palabra.escritura && palabra.escritura.isNotBlank()) {
        dao.actualizar(existente.copy(escritura = palabra.escritura, silabas = palabra.silabas))
      }
      return Guardado.YA_ESTABA
    }
    dao.insertar(
      Tarjeta(
        palabra = palabra.clave,
        escritura = palabra.escritura,
        silabas = palabra.silabas,
        pista = palabra.pista,
      )
    )
    return Guardado.CREADA
  }

  /** Las que todavía no han pasado por el ENRIQUECEDOR. */
  suspend fun sinEnriquecer(): List<Tarjeta> = colaDePractica().filter { !it.estaEnriquecida }

  /** Guarda lo que el ENRIQUECEDOR consiguió. Nunca toca el nivel ni la fecha de repaso. */
  suspend fun guardarEnriquecimiento(
    clave: String,
    definicion: String,
    ejemplo: String,
    pictograma: String?,
  ) {
    val tarjeta = dao.buscar(clave) ?: return
    dao.actualizar(
      tarjeta.copy(
        definicion = definicion,
        ejemplo = ejemplo,
        pictograma = pictograma ?: tarjeta.pictograma,
      )
    )
  }

  // --- Cuentos ---------------------------------------------------------------------------------

  /**
   * Las palabras que mejor le vienen a un cuento: las que peor lleva.
   *
   * Se ordenan por nivel ascendente —las de peldaño bajo son las que más se le resisten— y se
   * devuelve la ortografía real, no la clave normalizada: el cuento lo va a leer un niño.
   */
  suspend fun palabrasParaCuento(cuantas: Int): List<String> =
    colaDePractica().sortedBy { it.nivel }.take(cuantas).map { it.comoSeEscribe }

  suspend fun guardarCuento(titulo: String, texto: String, palabras: List<String>): Cuento {
    val cuento =
      Cuento(titulo = titulo, texto = texto, palabrasUsadas = palabras.joinToString(", "))
    val id = cuentos.insertar(cuento)
    return cuento.copy(id = id)
  }

  fun todosLosCuentos(): Flow<List<Cuento>> = cuentos.todos()

  /** Los títulos ya usados, para que el CUENTISTA no repita. Comprobación de SQL, no de modelo. */
  suspend fun titulosDeCuentos(): List<String> = cuentos.titulos()

  suspend fun tituloRepetido(titulo: String): Boolean = cuentos.cuantosConTitulo(titulo.trim()) > 0

  fun cuantosCuentos(): Flow<Int> = cuentos.cuantos()

  suspend fun cuentoPorId(id: Long): Cuento? = cuentos.porId(id)

  suspend fun borrarCuento(id: Long) = cuentos.borrar(id)

  /** Qué pasó al intentar guardar una palabra fallada. Se le cuenta al niño en pantalla. */
  enum class Guardado {
    /** Tarjeta nueva. */
    CREADA,

    /** Ya la estaba practicando: no se duplica ni se le reinicia el nivel. */
    YA_ESTABA,

    /** El cupo de palabras nuevas de la sesión está lleno. Se le enseña igual, pero no se guarda. */
    NO_CUPO,
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
    // Se normaliza aquí: quien llama tiene la palabra tal como se escribe ("estableció") y la clave
    // de la tabla es la forma sin tildes.
    val tarjeta = dao.buscar(DetectorErrores.normalizar(palabra)) ?: return
    dao.actualizar(Repaso.repasar(tarjeta, acierto = true, hoy = hoy))
  }
}
