package pe.masi.datos

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * La base de datos local. Sin cuentas, sin sincronización, sin nube.
 *
 * Todo lo que Masi sabe del niño vive en este archivo, dentro de su teléfono, y desaparece si se
 * desinstala la app. Es coherente con el resto del proyecto y, con el modelo también dentro del
 * dispositivo, es verificable: no hay ningún sitio al que estos datos pudieran salir.
 */
@Database(entities = [Tarjeta::class, Cuento::class], version = 4, exportSchema = true)
abstract class MasiDatabase : RoomDatabase() {

  abstract fun tarjetaDao(): TarjetaDao

  abstract fun cuentoDao(): CuentoDao

  companion object {
    /**
     * v1 → v2: la tarjeta guarda cómo se escribe la palabra de verdad.
     *
     * Hasta la v1 solo se guardaba la forma normalizada —minúsculas y sin tildes, que es como se
     * compara contra la transcripción—, así que la tarjeta de "estableció" decía `establecio`. Para
     * una app que enseña a leer, eso es enseñar la ortografía mal.
     *
     * Las tarjetas que ya existen se quedan con el campo vacío y siguen mostrándose como estaban
     * (ver `Tarjeta.comoSeEscribe`). **No se intenta reconstruir la tilde**: no hay forma de saber
     * si `mama` era "mamá" o "mama", y adivinar sería inventarse la palabra de un niño.
     */
    private val MIGRACION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE tarjetas ADD COLUMN escritura TEXT NOT NULL DEFAULT ''")
        }
      }

    /**
     * v2 → v3: la biblioteca de cuentos.
     *
     * Aditiva pura: crea una tabla nueva y no toca `tarjetas`. Ninguna palabra que el niño lleve
     * practicando se ve afectada, que es la única propiedad que de verdad importa aquí.
     *
     * El SQL está escrito a mano y debe coincidir EXACTAMENTE con lo que Room espera de la entidad
     * [Cuento]. Si no coincide, Room lo detecta al abrir y lanza; por eso `exportSchema` escribe
     * `app/schemas/3.json`, para poder compararlo.
     */
    private val MIGRACION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cuentos` (
              `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
              `titulo` TEXT NOT NULL,
              `texto` TEXT NOT NULL,
              `palabrasUsadas` TEXT NOT NULL,
              `creadoDiaEpoch` INTEGER NOT NULL
            )
            """
              .trimIndent()
          )
        }
      }

    /**
     * v3 → v4: la tarjeta guarda lo que el ENRIQUECEDOR le añade.
     *
     * Tres columnas de texto con valor por defecto vacío, así que las tarjetas que ya existen no
     * pierden nada y simplemente se ven como se veían. El enriquecimiento ocurre después, en
     * segundo plano, y es opcional por diseño: sin modelo disponible la app funciona igual.
     */
    private val MIGRACION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE tarjetas ADD COLUMN definicion TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE tarjetas ADD COLUMN ejemplo TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE tarjetas ADD COLUMN pictograma TEXT NOT NULL DEFAULT ''")
        }
      }

    /**
     * Las migraciones de esquema, en orden.
     *
     * Sin ellas, Room lanza `IllegalStateException` al abrir en **cualquier teléfono que ya tenga
     * la app instalada**. No en el emulador recién creado: justo en el del niño que lleva un mes
     * practicando.
     *
     * Deliberadamente NO se usa `fallbackToDestructiveMigration()`. Sería una línea y borraría sus
     * tarjetas en silencio; el `ALTER TABLE` de arriba cuesta cinco minutos.
     *
     * `exportSchema = true` escribe el esquema en `app/schemas/` para poder compararlo entre
     * versiones. Ese directorio se versiona con el código.
     */
    val MIGRACIONES: Array<Migration> = arrayOf(MIGRACION_1_2, MIGRACION_2_3, MIGRACION_3_4)

    @Volatile private var instancia: MasiDatabase? = null

    fun obtener(context: Context): MasiDatabase =
      instancia
        ?: synchronized(this) {
          instancia
            ?: Room.databaseBuilder(
                context.applicationContext,
                MasiDatabase::class.java,
                "masi.db",
              )
              .addMigrations(*MIGRACIONES)
              .build()
              .also { instancia = it }
        }
  }
}
