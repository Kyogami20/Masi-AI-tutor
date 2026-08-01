package pe.masi.datos

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * La base de datos local. Sin cuentas, sin sincronización, sin nube.
 *
 * Todo lo que Masi sabe del niño vive en este archivo, dentro de su teléfono, y desaparece si se
 * desinstala la app. Es coherente con el resto del proyecto y, con el modelo también dentro del
 * dispositivo, es verificable: no hay ningún sitio al que estos datos pudieran salir.
 */
@Database(entities = [Tarjeta::class], version = 1, exportSchema = true)
abstract class MasiDatabase : RoomDatabase() {

  abstract fun tarjetaDao(): TarjetaDao

  companion object {
    /**
     * Las migraciones de esquema, en orden. Vacío mientras la versión sea 1.
     *
     * Está aquí desde ya porque el siguiente campo que se añada a [Tarjeta] —una definición, la
     * ruta de una imagen, ejemplos de uso— sube la versión, y sin una migración Room lanza
     * `IllegalStateException` al abrir en **cualquier teléfono que ya tenga la app instalada**. No
     * en el emulador recién creado: justo en el del niño que lleva un mes practicando.
     *
     * Deliberadamente NO se usa `fallbackToDestructiveMigration()`. Sería una línea y borraría sus
     * tarjetas en silencio; escribir el `ALTER TABLE` correspondiente cuesta cinco minutos.
     *
     * `exportSchema = true` escribe el esquema en `app/schemas/` para poder compararlo entre
     * versiones. Ese directorio se versiona con el código.
     */
    val MIGRACIONES: Array<Migration> = emptyArray()

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
