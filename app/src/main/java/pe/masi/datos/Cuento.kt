package pe.masi.datos

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Un cuento que Masi escribió con las palabras que al niño le costaban.
 *
 * Se guarda por dos razones. La obvia: generarlo cuesta casi un minuto y releerlo no debería costar
 * nada. La menos obvia: **un cuento guardado se puede leer sin el modelo cargado**, que es lo que
 * hace útil soltar el motor en segundo plano. La biblioteca funciona con la app en 100 MB de RAM.
 */
@Entity(tableName = "cuentos")
data class Cuento(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val titulo: String,
  val texto: String,

  /**
   * Las palabras que el cuento venía a practicar, separadas por coma.
   *
   * Sin tabla aparte a propósito: son tres o cuatro cadenas por cuento y nunca se consultan al
   * revés. Una tabla de unión aquí sería ceremonia sin beneficio.
   */
  val palabrasUsadas: String,
  val creadoDiaEpoch: Long = LocalDate.now().toEpochDay(),
) {
  val palabras: List<String>
    get() = palabrasUsadas.split(",").map { it.trim() }.filter { it.isNotBlank() }

  val creado: LocalDate
    get() = LocalDate.ofEpochDay(creadoDiaEpoch)
}
