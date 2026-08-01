package pe.masi.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "MasiGrabador"

/**
 * Graba la voz del niño en el formato exacto que pide Gemma 4: 16 kHz, mono, PCM de 16 bits.
 *
 * Que Android pueda grabar directamente en ese formato ahorra todo el remuestreo que haría falta en
 * un navegador. Se usa `AudioRecord` y no `MediaRecorder` porque necesitamos las muestras crudas,
 * no un archivo comprimido.
 *
 * **El audio nunca toca el disco.** Se acumula en memoria, se le pone cabecera WAV, se le pasa al
 * modelo y se descarta. Es la voz de un menor: con el modelo dentro del teléfono no puede salir del
 * dispositivo aunque quisiéramos, y tampoco se queda guardada.
 */
class GrabadorAudio {

  private val _amplitud = MutableStateFlow(0)

  /** Amplitud de pico, 0..32767. Alimenta la animación del micrófono. */
  val amplitud: StateFlow<Int> = _amplitud.asStateFlow()

  private val _milis = MutableStateFlow(0L)
  val milisTranscurridos: StateFlow<Long> = _milis.asStateFlow()

  private val grabando = AtomicBoolean(false)
  private var recorder: AudioRecord? = null

  val estaGrabando: Boolean
    get() = grabando.get()

  /**
   * Graba hasta que alguien llame a [detener] o hasta agotar [maxSegundos].
   *
   * Suspende hasta terminar y devuelve el clip ya con cabecera WAV, listo para
   * `Content.AudioBytes`.
   *
   * El permiso RECORD_AUDIO lo comprueba quien llama.
   */
  @SuppressLint("MissingPermission")
  suspend fun grabar(maxSegundos: Int): ByteArray =
    withContext(Dispatchers.IO) {
      if (!grabando.compareAndSet(false, true)) {
        Log.w(TAG, "Ya había una grabación en marcha")
        return@withContext ByteArray(0)
      }

      val tamanoMinimo = AudioRecord.getMinBufferSize(SAMPLE_RATE, CANAL, FORMATO)
      val salida = ByteArrayOutputStream()
      var grabadora: AudioRecord? = null

      try {
        grabadora =
          AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CANAL, FORMATO, tamanoMinimo)
        recorder = grabadora
        grabadora.startRecording()

        val buffer = ByteArray(tamanoMinimo)
        val inicio = System.currentTimeMillis()
        val topeMs = maxSegundos * 1000L
        _milis.value = 0L

        while (grabando.get()) {
          val leidos = grabadora.read(buffer, 0, buffer.size)
          if (leidos > 0) {
            salida.write(buffer, 0, leidos)
            _amplitud.value = picoDe(buffer, leidos)
          }
          _milis.value = System.currentTimeMillis() - inicio
          if (_milis.value >= topeMs) break
        }
      } catch (e: Exception) {
        Log.e(TAG, "Falló la grabación", e)
      } finally {
        grabando.set(false)
        try {
          if (grabadora?.recordingState == AudioRecord.RECORDSTATE_RECORDING) grabadora.stop()
        } catch (e: IllegalStateException) {
          Log.w(TAG, "La grabadora ya estaba detenida", e)
        }
        grabadora?.release()
        recorder = null
        _amplitud.value = 0
      }

      val pcm = salida.toByteArray()
      salida.reset()
      Log.d(TAG, "Grabados ${pcm.size} bytes (${pcm.size / (SAMPLE_RATE * 2f)} s)")
      if (pcm.isEmpty()) ByteArray(0) else pcmAWav(pcm)
    }

  /** Corta la grabación en curso. [grabar] devuelve entonces lo capturado hasta ahora. */
  fun detener() {
    grabando.set(false)
  }

  private fun picoDe(buffer: ByteArray, bytesLeidos: Int): Int {
    var pico = 0
    var i = 0
    // PCM 16 bits little-endian: dos bytes por muestra.
    while (i + 1 < bytesLeidos) {
      val muestra = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
      val absoluto = if (muestra == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(muestra)
      if (absoluto > pico) pico = absoluto
      i += 2
    }
    return pico
  }

  companion object {
    /** El que pide Gemma 4. No cambiar. */
    const val SAMPLE_RATE = 16_000

    private const val CANAL = AudioFormat.CHANNEL_IN_MONO
    private const val FORMATO = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Topes de duración.
     *
     * El modelo admite hasta 30 s, pero aquí se usan clips mucho más cortos y por dos razones que
     * apuntan en la misma dirección:
     *  - Menos contexto = menos oportunidad de que el modelo "arregle" el error del niño.
     *  - Menos contexto = generación notablemente más rápida (la caché KV es más pequeña).
     *
     * 30 s de audio ocupan unos 750 tokens y frenan TODO lo que se genere después; 6 s ocupan 150.
     */
    /**
     * Un fragmento de lectura. Va emparejado con `Segmentador.MAX_PALABRAS`: a las ~1 palabra por
     * segundo que lee un niño de 7 años con dificultades, 10 palabras son unos 10 s, y quedan dos
     * de margen. Es una red de seguridad, no el modo normal de terminar: lo habitual es que el niño
     * corte antes con "Ya terminé".
     */
    const val MAX_SEG_FRASE = 12

    /** Una palabra suelta: al fallarla o en una tarjeta. Sin contexto que el modelo autocorrija. */
    const val MAX_SEG_PALABRA = 3

    /**
     * Envuelve el PCM crudo en un WAV mínimo de 44 bytes.
     *
     * `Content.AudioBytes` espera un contenedor, no muestras sueltas. Es la misma cabecera que
     * construye Google AI Edge Gallery antes de pasar el audio al motor.
     */
    fun pcmAWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
      val canales = 1
      val bitsPorMuestra = 16
      val byteRate = sampleRate * canales * bitsPorMuestra / 8
      val alineacion = canales * bitsPorMuestra / 8

      val cabecera =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
          put("RIFF".toByteArray(Charsets.US_ASCII))
          putInt(36 + pcm.size) // tamaño del archivo menos los 8 primeros bytes
          put("WAVE".toByteArray(Charsets.US_ASCII))
          put("fmt ".toByteArray(Charsets.US_ASCII))
          putInt(16) // tamaño del bloque fmt para PCM
          putShort(1) // 1 = PCM sin comprimir
          putShort(canales.toShort())
          putInt(sampleRate)
          putInt(byteRate)
          putShort(alineacion.toShort())
          putShort(bitsPorMuestra.toShort())
          put("data".toByteArray(Charsets.US_ASCII))
          putInt(pcm.size)
        }

      return cabecera.array() + pcm
    }
  }
}
