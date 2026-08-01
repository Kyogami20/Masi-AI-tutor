package pe.masi.datos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CuentoDao {

  /** Los más recientes primero: el que acaba de generarse es el que se quiere leer. */
  @Query("SELECT * FROM cuentos ORDER BY creadoDiaEpoch DESC, id DESC")
  fun todos(): Flow<List<Cuento>>

  @Query("SELECT * FROM cuentos WHERE id = :id") suspend fun porId(id: Long): Cuento?

  @Query("SELECT COUNT(*) FROM cuentos") fun cuantos(): Flow<Int>

  /**
   * Los títulos que ya existen. Se usan para no repetirlos.
   *
   * Es una consulta trivial y ahí está la gracia: comprobar si un título está repetido es trabajo de
   * SQL —exacto e instantáneo—, no del modelo. Al modelo solo se le vuelve a pedir un título si esta
   * comparación dice que hace falta. Mismo criterio que con los pictogramas.
   *
   * `COLLATE NOCASE` para que "El nido de Rosa" y "el nido de rosa" cuenten como el mismo.
   */
  @Query("SELECT titulo FROM cuentos") suspend fun titulos(): List<String>

  @Query("SELECT COUNT(*) FROM cuentos WHERE titulo = :titulo COLLATE NOCASE")
  suspend fun cuantosConTitulo(titulo: String): Int

  @Insert suspend fun insertar(cuento: Cuento): Long

  @Query("DELETE FROM cuentos WHERE id = :id") suspend fun borrar(id: Long)
}
