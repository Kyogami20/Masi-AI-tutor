package pe.masi.descarga

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import pe.masi.motor.ModeloLocal

private const val TAG = "MasiImportador"
private const val BUFFER = 256 * 1024
private const val INTERVALO_AVISO_MS = 400L

/**
 * Copia un `.litertlm` elegido con el selector de archivos del sistema.
 *
 * Es el camino sin red: el archivo llega al teléfono por USB, por Bluetooth o desde otro teléfono
 * que ya lo tenga, el adulto lo busca en Archivos y Masi lo copia a su carpeta. En Huancavelica
 * esto no es un plan B, es el plan.
 *
 * Se copia en vez de leerse en su sitio porque el motor nativo necesita una ruta de archivo real, y
 * un `content://` no lo es.
 */
object ImportadorSAF {

  /** Tipos MIME a pasar a `ActivityResultContracts.OpenDocument`. */
  val TIPOS = arrayOf("*/*")

  fun importar(context: Context, origen: Uri): Flow<ProgresoDescarga> =
    flow {
        val destino = ModeloLocal.archivo(context)
        val parcial = ModeloLocal.archivoParcial(context)
        val totales = tamanoDe(context, origen) ?: ModeloLocal.TAMANO_BYTES
        var copiados = 0L

        try {
          val entrada =
            context.contentResolver.openInputStream(origen)
              ?: run {
                emit(ProgresoDescarga.Fallida("No se pudo abrir el archivo", 0L))
                return@flow
              }

          entrada.use { origenStream ->
            FileOutputStream(parcial, /* append= */ false).use { salida ->
              val buffer = ByteArray(BUFFER)
              var ultimoAviso = System.currentTimeMillis()
              var bytesDesdeAviso = 0L

              while (true) {
                currentCoroutineContext().ensureActive()
                val leidos = origenStream.read(buffer)
                if (leidos < 0) break
                salida.write(buffer, 0, leidos)
                copiados += leidos
                bytesDesdeAviso += leidos

                val ahora = System.currentTimeMillis()
                val transcurrido = ahora - ultimoAviso
                if (transcurrido >= INTERVALO_AVISO_MS) {
                  emit(
                    ProgresoDescarga.EnCurso(
                      recibidos = copiados,
                      totales = totales,
                      bytesPorSegundo = bytesDesdeAviso * 1000 / transcurrido,
                    )
                  )
                  ultimoAviso = ahora
                  bytesDesdeAviso = 0L
                }
              }
            }
          }

          if (!parcial.renameTo(destino)) {
            emit(ProgresoDescarga.Fallida("No se pudo guardar el modelo", copiados))
            return@flow
          }
          Log.i(TAG, "Modelo importado: ${destino.absolutePath} (${destino.length()} bytes)")
          emit(ProgresoDescarga.Terminada(destino.absolutePath))
        } catch (e: Exception) {
          Log.w(TAG, "Falló la importación a los $copiados bytes", e)
          parcial.delete()
          emit(ProgresoDescarga.Fallida(e.message ?: "No se pudo copiar el archivo", copiados))
        }
      }
      .flowOn(Dispatchers.IO)

  private fun tamanoDe(context: Context, uri: Uri): Long? =
    try {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val col = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (col >= 0 && cursor.moveToFirst() && !cursor.isNull(col)) cursor.getLong(col) else null
      }
    } catch (e: Exception) {
      null
    }
}
