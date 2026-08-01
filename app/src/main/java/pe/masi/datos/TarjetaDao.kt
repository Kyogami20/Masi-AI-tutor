package pe.masi.datos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TarjetaDao {

  @Query("SELECT * FROM tarjetas ORDER BY proximoDiaEpoch ASC") fun todas(): Flow<List<Tarjeta>>

  /**
   * Todas las tarjetas, con las que ya tocan primero.
   *
   * Deliberadamente **no** filtra por fecha. La repetición espaciada decide qué conviene repasar
   * hoy, no qué se le permite al niño practicar: si quiere volver a una palabra que ya practicó,
   * tiene que poder. Una pantalla de práctica vacía es una puerta cerrada.
   */
  @Query(
    """
    SELECT * FROM tarjetas
    ORDER BY CASE WHEN proximoDiaEpoch <= :hoy THEN 0 ELSE 1 END ASC,
             proximoDiaEpoch ASC,
             nivel ASC,
             palabra ASC
    """
  )
  suspend fun ordenadasParaPracticar(hoy: Long): List<Tarjeta>

  @Query("SELECT COUNT(*) FROM tarjetas WHERE proximoDiaEpoch <= :hoy")
  fun cuentaPendientes(hoy: Long): Flow<Int>

  @Query("SELECT COUNT(*) FROM tarjetas WHERE creadaDiaEpoch >= :desde")
  fun cuentaCreadasDesde(desde: Long): Flow<Int>

  @Query("SELECT * FROM tarjetas WHERE palabra = :palabra") suspend fun buscar(palabra: String): Tarjeta?

  /**
   * Ignora si ya existe: una palabra que se vuelve a fallar no debe perder el nivel que ya tenía
   * por el simple hecho de reaparecer. El nivel lo baja `repasar()`, y solo tras un repaso real.
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertar(tarjeta: Tarjeta)

  @Update suspend fun actualizar(tarjeta: Tarjeta)

  @Delete suspend fun borrar(tarjeta: Tarjeta)

  @Query("DELETE FROM tarjetas WHERE palabra = :palabra") suspend fun borrarPalabra(palabra: String)

  @Query("DELETE FROM tarjetas") suspend fun borrarTodas()
}
