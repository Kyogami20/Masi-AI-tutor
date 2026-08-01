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
  /**
   * La clave: la palabra **normalizada**, en minúsculas y sin tildes. Una palabra, una tarjeta.
   *
   * Es lo que deduplica, y por eso "Perro" al empezar una oración y "perro" a media frase son la
   * misma tarjeta en vez de dos. Lo que se le enseña al niño es [escritura], no esto.
   */
  @PrimaryKey val palabra: String,

  /**
   * Cómo está escrita de verdad en el libro: "estableció", "Baltimore".
   *
   * Existe porque [palabra] va sin tildes —hace falta así para comparar contra una transcripción,
   * que con los acentos no es de fiar— y una tarjeta que dice "establecio" le enseña al niño la
   * ortografía equivocada, que es exactamente lo contrario de lo que hace esta app.
   *
   * Vacío en las tarjetas creadas antes de la versión 2 del esquema. Usar [comoSeEscribe].
   */
  val escritura: String = "",

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

  /**
   * Qué significa la palabra, en una frase que un niño de 7 años entiende.
   *
   * Lo escribe el ENRIQUECEDOR después de crear la tarjeta, en segundo plano. Vacío mientras no
   * haya pasado por ahí, o si el modelo no estaba disponible: la tarjeta funciona igual.
   */
  val definicion: String = "",

  /** Una frase corriente que usa la palabra. */
  val ejemplo: String = "",

  /**
   * Archivo del pictograma en `assets/pictogramas/`, si se encontró alguno.
   *
   * Puede corresponder a un concepto emparentado y no a la palabra: para "estableció" el modelo
   * busca "construir". Eso es deliberado — ver `HerramientasPictograma`.
   */
  val pictograma: String = "",
) {
  val proxima: LocalDate
    get() = LocalDate.ofEpochDay(proximoDiaEpoch)

  /**
   * Lo que se muestra en pantalla. **Siempre esto, nunca [palabra] directamente.**
   *
   * El respaldo cubre las tarjetas guardadas antes de la versión 2, que no tienen ortografía
   * guardada: se enseñan como estaban, sin tilde, en vez de desaparecer.
   */
  val comoSeEscribe: String
    get() = escritura.ifBlank { palabra }

  val tienePictograma: Boolean
    get() = pictograma.isNotBlank()

  /** Si ya pasó por el ENRIQUECEDOR. Sirve para no volver a pedírselo al modelo. */
  val estaEnriquecida: Boolean
    get() = definicion.isNotBlank()

  /**
   * Si la repetición espaciada la sitúa para hoy o antes.
   *
   * Sirve para destacarla en la cuadrícula, **no para bloquear las demás**: la escalera de
   * intervalos decide el orden, nunca el permiso para practicar.
   */
  val tocaHoy: Boolean
    get() = proximoDiaEpoch <= LocalDate.now().toEpochDay()
}
