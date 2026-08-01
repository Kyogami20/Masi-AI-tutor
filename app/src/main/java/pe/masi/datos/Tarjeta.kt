package pe.masi.datos

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Una palabra que el niño falló y que Masi le va a devolver otro día.
 *
 * La fecha se guarda como día epoch (un entero) en vez de como texto: Room no necesita conversores
 * y las comparaciones son aritmética entera.
 */
@Entity(tableName = "tarjetas")
data class Tarjeta(
  /** La palabra correcta. Es la clave: una palabra, una tarjeta. */
  @PrimaryKey val palabra: String,

  /** La misma palabra con guiones entre sílabas, para mostrarla. */
  val silabas: String,

  /** La pista mnemotécnica que dio el TUTOR el día del fallo. */
  val pista: String,

  /** Posición en la escalera de intervalos. 0 = recién fallada. */
  val nivel: Int = 0,

  /** Día en que toca repasarla, como día epoch. */
  val proximoDiaEpoch: Long = LocalDate.now().toEpochDay(),

  /** Cuándo se creó, para poder mostrar "practicaste X palabras esta semana". */
  val creadaDiaEpoch: Long = LocalDate.now().toEpochDay(),

  /** Cuántas veces se acertó. Solo para el resumen del adulto; nunca se muestra al niño. */
  val aciertos: Int = 0,
) {
  val proxima: LocalDate
    get() = LocalDate.ofEpochDay(proximoDiaEpoch)
}
