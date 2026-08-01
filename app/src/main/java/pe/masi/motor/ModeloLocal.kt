package pe.masi.motor

import android.content.Context
import java.io.File

/**
 * Dónde vive el archivo del modelo y en qué estado está.
 *
 * El modelo NO es código: es un archivo de datos que el motor lee. Puede llegar al teléfono de tres
 * maneras, y a Masi le da igual cuál:
 *  1. Copiado por USB / Bluetooth / `adb push` a la carpeta de la app.
 *  2. Descargado por la propia app (ver `pe.masi.descarga.DescargaModelo`).
 *  3. Elegido con el selector de archivos del sistema (ver `pe.masi.descarga.ImportadorSAF`).
 *
 * Ruta para copiarlo a mano:
 * ```
 * adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/pe.masi/files/modelo/
 * ```
 */
object ModeloLocal {

  /** Nombre del archivo oficial de `litert-community`. No exportes el tuyo: no lleva audio. */
  const val NOMBRE_ARCHIVO = "gemma-4-E2B-it.litertlm"

  /** Tamaño exacto del archivo oficial, en bytes (≈ 2,59 GB). */
  const val TAMANO_BYTES = 2_588_147_712L

  /**
   * URL directa con el commit fijado. Fijar el commit importa: si Hugging Face publica otra
   * revisión, el archivo cambia de tamaño y la reanudación de la descarga se rompería a mitad.
   */
  const val URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
      "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true"

  /** Por debajo de esto damos por hecho que el archivo está a medias o es otra cosa. */
  private const val TAMANO_MINIMO_PLAUSIBLE = 1_500_000_000L

  fun directorio(context: Context): File =
    File(context.getExternalFilesDir(null), "modelo").apply { mkdirs() }

  fun archivo(context: Context): File = File(directorio(context), NOMBRE_ARCHIVO)

  /** El archivo temporal de la descarga en curso. Se renombra al terminar. */
  fun archivoParcial(context: Context): File = File(directorio(context), "$NOMBRE_ARCHIVO.parcial")

  fun estado(context: Context): EstadoModelo {
    val f = archivo(context)
    if (f.isFile && f.length() >= TAMANO_MINIMO_PLAUSIBLE) {
      return EstadoModelo.Listo(f.absolutePath, f.length())
    }
    val parcial = archivoParcial(context)
    if (parcial.isFile && parcial.length() > 0L) {
      return EstadoModelo.Parcial(parcial.length(), TAMANO_BYTES)
    }
    return EstadoModelo.Ausente
  }
}

sealed interface EstadoModelo {
  /** No hay nada que cargar todavía. */
  data object Ausente : EstadoModelo

  /** Hay una descarga a medias que se puede reanudar. */
  data class Parcial(val bytesRecibidos: Long, val bytesTotales: Long) : EstadoModelo

  data class Listo(val ruta: String, val bytes: Long) : EstadoModelo
}
