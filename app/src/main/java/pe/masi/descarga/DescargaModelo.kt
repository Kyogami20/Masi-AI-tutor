package pe.masi.descarga

import android.content.Context
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import pe.masi.motor.ModeloLocal

private const val TAG = "MasiDescarga"
private const val BUFFER = 256 * 1024

/** Cada cuánto se avisa a la interfaz. Emitir por cada bloque saturaría la recomposición. */
private const val INTERVALO_AVISO_MS = 400L

sealed interface ProgresoDescarga {
  data class EnCurso(val recibidos: Long, val totales: Long, val bytesPorSegundo: Long) :
    ProgresoDescarga {
    val fraccion: Float
      get() = if (totales <= 0L) 0f else (recibidos.toDouble() / totales).toFloat()

    val segundosRestantes: Long
      get() = if (bytesPorSegundo <= 0L) -1L else (totales - recibidos) / bytesPorSegundo
  }

  data class Terminada(val ruta: String) : ProgresoDescarga

  data class Fallida(val mensaje: String, val recibidos: Long) : ProgresoDescarga
}

/**
 * Descarga el archivo del modelo desde Hugging Face, con reanudación.
 *
 * Son 2,6 GB por una conexión que probablemente no sea buena, así que cortarse a mitad es lo
 * normal, no la excepción: se escribe a un `.parcial` y se reanuda con la cabecera `Range`. El
 * archivo solo pasa a llamarse como toca cuando está entero.
 *
 * Esta es la ÚNICA parte de Masi que toca la red, y solo se ejecuta una vez en la vida de la app.
 * A partir de ahí el teléfono puede quedarse en modo avión para siempre.
 */
object DescargaModelo {

  fun descargar(context: Context): Flow<ProgresoDescarga> =
    flow {
        val destino = ModeloLocal.archivo(context)
        val parcial = ModeloLocal.archivoParcial(context)
        var recibidos = if (parcial.isFile) parcial.length() else 0L

        var conexion: HttpURLConnection? = null
        try {
          conexion = (URL(ModeloLocal.URL).openConnection() as HttpURLConnection)
          conexion.connectTimeout = 30_000
          conexion.readTimeout = 30_000
          conexion.instanceFollowRedirects = true
          if (recibidos > 0L) {
            conexion.setRequestProperty("Range", "bytes=$recibidos-")
          }
          conexion.connect()

          val codigo = conexion.responseCode
          // 206 = el servidor aceptó reanudar. 200 = empieza de cero (hay que descartar lo previo).
          if (codigo == HttpURLConnection.HTTP_OK && recibidos > 0L) {
            Log.w(TAG, "El servidor no admite reanudar; se empieza de cero")
            recibidos = 0L
          } else if (codigo != HttpURLConnection.HTTP_OK && codigo != HttpURLConnection.HTTP_PARTIAL) {
            emit(ProgresoDescarga.Fallida("El servidor respondió $codigo", recibidos))
            return@flow
          }

          val totales =
            if (recibidos > 0L) recibidos + conexion.contentLengthLong
            else conexion.contentLengthLong.takeIf { it > 0 } ?: ModeloLocal.TAMANO_BYTES

          conexion.inputStream.use { entrada ->
            FileOutputStream(parcial, /* append= */ recibidos > 0L).use { salida ->
              val buffer = ByteArray(BUFFER)
              var ultimoAviso = System.currentTimeMillis()
              var bytesDesdeAviso = 0L

              while (true) {
                currentCoroutineContext().ensureActive()
                val leidos = entrada.read(buffer)
                if (leidos < 0) break
                salida.write(buffer, 0, leidos)
                recibidos += leidos
                bytesDesdeAviso += leidos

                val ahora = System.currentTimeMillis()
                val transcurrido = ahora - ultimoAviso
                if (transcurrido >= INTERVALO_AVISO_MS) {
                  emit(
                    ProgresoDescarga.EnCurso(
                      recibidos = recibidos,
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

          // **Verificar el tamaño antes de dar el archivo por bueno.**
    //
    // Si el servidor o un proxy cierra la conexión limpiamente a los 2,0 GB, `read` devuelve -1 sin
    // lanzar nada: el bucle termina como si hubiera acabado bien. Antes se renombraba igual, y
    // `ModeloLocal` solo exige 1,5 GB, así que un modelo truncado pasaba por bueno y reventaba
    // dentro del motor con un fallo nativo imposible de diagnosticar.
    if (totales > 0 && parcial.length() != totales) {
      Log.w(TAG, "Descarga incompleta: ${parcial.length()} de $totales bytes")
      emit(
        ProgresoDescarga.Fallida(
          "La descarga se cortó antes de terminar. Vuelve a intentarlo: se reanuda donde iba.",
          recibidos = parcial.length(),
        )
      )
      return@flow
    }

    if (!parcial.renameTo(destino)) {
            emit(ProgresoDescarga.Fallida("No se pudo guardar el modelo", recibidos))
            return@flow
          }
          Log.i(TAG, "Modelo descargado: ${destino.absolutePath} (${destino.length()} bytes)")
          emit(ProgresoDescarga.Terminada(destino.absolutePath))
        } catch (e: IOException) {
          // Lo descargado se queda en el .parcial: al reintentar se sigue desde ahí.
          Log.w(TAG, "Descarga interrumpida a los $recibidos bytes", e)
          emit(ProgresoDescarga.Fallida(e.message ?: "Se cortó la descarga", recibidos))
        } finally {
          conexion?.disconnect()
        }
      }
      .flowOn(Dispatchers.IO)
}
