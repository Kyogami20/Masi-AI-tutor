package pe.masi.motor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MasiMotor"

/**
 * Tope de contexto: prompt + imagen/audio + respuesta, todo junto.
 *
 * Es el mismo valor que Google AI Edge Gallery usa para Gemma 4 E2B. Quedarse corto aquí no da un
 * error: da una respuesta **cortada a media frase**, con el JSON sin cerrar, que el parser rechaza
 * y que en pantalla se ve como "no entendí la foto". Una página de libro ocupa ~560 tokens de
 * imagen más 150–400 de texto transcrito, así que 2048 iba demasiado justo.
 */
private const val MAX_TOKENS = 4096

/**
 * Los tres agentes de Masi. Un solo modelo en RAM, tres personalidades.
 *
 * Cada rol es una `Conversation` distinta: su propio system prompt, su propio historial y su propia
 * caché KV. Las temperaturas no son arbitrarias:
 *  - LECTOR y ESCUCHA transcriben, y transcribir es determinista: creatividad = alucinación.
 *  - TUTOR explica, y ahí sí queremos que no repita la misma frase cada vez.
 */
enum class Rol(
  val systemPrompt: String,
  val temperatura: Double,
  val topK: Int,
  val topP: Double,
) {
  LECTOR(Prompts.LECTOR, temperatura = 0.2, topK = 40, topP = 0.95),
  ESCUCHA(Prompts.ESCUCHA, temperatura = 0.1, topK = 10, topP = 0.95),
  TUTOR(Prompts.TUTOR, temperatura = 0.7, topK = 64, topP = 0.95),
}

sealed interface EstadoMotor {
  /** Todavía no hay archivo de modelo en el dispositivo. */
  data object SinModelo : EstadoMotor

  /** `engine.initialize()` en curso. Puede tardar hasta 10 s: hay que taparlo con animación. */
  data object Cargando : EstadoMotor

  data class Listo(val aceleracionGpu: Boolean, val mtp: Boolean) : EstadoMotor

  data class Error(val mensaje: String) : EstadoMotor
}

/**
 * El motor. Se crea una vez, vive en `MasiApplication` y se cierra al morir el proceso.
 *
 * Dos reglas de este archivo, ambas caras de aprender por las malas:
 *
 * 1. **Nada de esto puede correr en el hilo de la interfaz.** `initialize()` tarda segundos y la
 *    generación tarda más.
 * 2. **El recolector de basura de Kotlin no libera memoria nativa de C++.** Hay que cerrar a mano,
 *    primero la `Conversation` y después el `Engine`. Es la causa número uno de cierres por
 *    memoria en apps con modelo local.
 *
 * Sobre la RAM: el documento maestro plantea mantener las tres conversaciones vivas a la vez. En un
 * teléfono de 6 GB eso son tres cachés KV compitiendo, así que aquí se mantiene **una sola viva** y
 * se recrea al cambiar de rol. Recrear es barato — no recarga el modelo, solo reinicia la caché.
 */
class MotorMasi(private val context: Context) {

  private val _estado = MutableStateFlow<EstadoMotor>(EstadoMotor.SinModelo)
  val estado: StateFlow<EstadoMotor> = _estado.asStateFlow()

  /** Serializa el acceso al motor: LiteRT-LM no admite dos generaciones a la vez. */
  private val cerrojo = Mutex()

  private var engine: Engine? = null
  private var conversacion: Conversation? = null
  private var rolActual: Rol? = null

  val estaListo: Boolean
    get() = engine != null

  /**
   * Enciende el motor. Idempotente: si ya está encendido, no hace nada.
   *
   * @return true si al terminar el motor está utilizable.
   */
  @OptIn(ExperimentalApi::class)
  suspend fun arrancar(): Boolean =
    cerrojo.withLock {
      if (engine != null) return@withLock true

      val modelo = ModeloLocal.estado(context)
      if (modelo !is EstadoModelo.Listo) {
        _estado.value = EstadoMotor.SinModelo
        return@withLock false
      }

      _estado.value = EstadoMotor.Cargando

      withContext(Dispatchers.IO) {
        try {
          // El archivo dice si trae el modelo borrador para decodificación especulativa (MTP).
          // Activarlo a ciegas en un archivo que no lo trae hace fallar la inicialización.
          val soportaMtp =
            try {
              Capabilities(modelo.ruta).use { it.hasSpeculativeDecodingSupport() }
            } catch (e: Exception) {
              Log.w(TAG, "No se pudo inspeccionar el modelo; se asume sin MTP", e)
              false
            }

          val config =
            EngineConfig(
              modelPath = modelo.ruta,
              backend = Backend.GPU(),
              // No son preferencias, son restricciones del runtime para Gemma:
              // la visión va en GPU y el audio va en CPU.
              visionBackend = Backend.GPU(),
              audioBackend = Backend.CPU(),
              maxNumTokens = MAX_TOKENS,
              // Acelera notablemente la SEGUNDA carga y las siguientes.
              cacheDir = context.cacheDir.absolutePath,
            )

          ExperimentalFlags.enableSpeculativeDecoding = soportaMtp
          val nuevo = Engine(config)
          nuevo.initialize()
          ExperimentalFlags.enableSpeculativeDecoding = false

          engine = nuevo
          _estado.value = EstadoMotor.Listo(aceleracionGpu = true, mtp = soportaMtp)
          Log.i(TAG, "Motor listo. MTP=$soportaMtp, maxTokens=$MAX_TOKENS")
          true
        } catch (e: Exception) {
          Log.e(TAG, "Falló la inicialización del motor", e)
          ExperimentalFlags.enableSpeculativeDecoding = false
          engine = null
          _estado.value = EstadoMotor.Error(e.message ?: "No se pudo encender el motor")
          false
        }
      }
    }

  /**
   * Genera una respuesta en streaming. Cada emisión es un trozo nuevo, no el texto acumulado.
   *
   * Se usa streaming siempre: ver letras aparecer es la diferencia entre "está pensando" y "se
   * colgó", y un niño de 7 años abandona con 3 segundos de pantalla muerta.
   *
   * Si quien llama cancela el Flow (por timeout, o porque el niño salió de la pantalla), se avisa
   * al motor con `cancelProcess()` para que suelte el trabajo en curso.
   */
  fun generar(
    rol: Rol,
    texto: String,
    imagen: ByteArray? = null,
    audioWav: ByteArray? = null,
    orden: OrdenContenido = OrdenContenido.GALLERY,
    conHistorial: Boolean = false,
  ): Flow<String> = flow {
    // El motor se suelta al pasar la app a segundo plano, así que puede llegar aquí apagado. Se
    // reenciende solo: cuesta unos 10 s que la animación de carga ya está tapando, y es mucho mejor
    // que devolverle al niño un error por algo que la app puede resolver sola.
    //
    // Va FUERA del cerrojo a propósito: `arrancar()` lo toma por su cuenta y un Mutex de Kotlin no
    // es reentrante.
    if (engine == null && !arrancar()) {
      // Sin motor no hay nada que emitir. El flujo se cierra vacío, el parser devuelve null y
      // quien llamó lo presenta como "vamos a intentarlo otra vez", que es la respuesta correcta.
      Log.w(TAG, "Se pidió generar con el motor apagado y no se pudo reencender")
      return@flow
    }
    cerrojo.withLock {
      val conv = prepararConversacion(rol, conHistorial)
      val contenidos = ordenar(texto = texto, imagen = imagen, audioWav = audioWav, orden = orden)
      emitAll(transmitir(conv, contenidos))
    }
  }

  /** Junta el texto completo. Cómodo cuando la respuesta se parsea entera (JSON) y no se muestra. */
  suspend fun generarCompleto(
    rol: Rol,
    texto: String,
    imagen: ByteArray? = null,
    audioWav: ByteArray? = null,
    orden: OrdenContenido = OrdenContenido.GALLERY,
    conHistorial: Boolean = false,
  ): String {
    val sb = StringBuilder()
    generar(rol, texto, imagen, audioWav, orden, conHistorial).collect { sb.append(it) }
    return sb.toString()
  }

  /**
   * Fuerza la carga del encoder de visión con una imagen mínima.
   *
   * Los encoders de visión y de audio del `.litertlm` **se cargan bajo demanda**, no al encender el
   * motor. Por eso la primera foto de la sesión paga entre 10 y 20 segundos extra que las
   * siguientes no pagan. Gastarlos aquí, en segundo plano y mientras el niño está en la pantalla de
   * inicio, los quita de en medio justo cuando más molestan: la primera vez que alguien enseña la
   * app.
   *
   * Se lanza sin bloquear y da igual si falla.
   */
  suspend fun precalentarVision() {
    if (engine == null) return
    try {
      val lienzo =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
      val png = ByteArrayOutputStream().also { lienzo.compress(Bitmap.CompressFormat.PNG, 100, it) }
      val respuesta =
        withTimeoutOrNull(90_000L) {
          generarCompleto(rol = Rol.LECTOR, texto = "Lee esta página.", imagen = png.toByteArray())
        }
      Log.i(TAG, "Encoder de visión precalentado (respuesta: ${respuesta?.take(40)})")
    } catch (e: Exception) {
      Log.w(TAG, "No se pudo precalentar la visión; no pasa nada", e)
    }
  }

  /** Olvida el historial del rol activo sin tocar el motor. */
  suspend fun reiniciarConversacion() {
    cerrojo.withLock {
      cerrarConversacion()
      rolActual = null
    }
  }

  /**
   * Suelta el motor **esperando a que no haya nada generando**. Es la forma segura de apagarlo con
   * la app viva.
   *
   * La diferencia con [liberar] no es cosmética, es la diferencia entre funcionar y un SIGSEGV.
   * `engine.close()` destruye memoria nativa que el hilo de inferencia está usando en ese mismo
   * instante; hacerlo a mitad de una generación es un uso después de liberar, y el proceso se cae
   * en seco sin excepción de Java ni nada que mirar en el logcat salvo un volcado de registros.
   *
   * Pasó de verdad: un `onTrimMemory` cerraba el motor mientras el LECTOR miraba la foto.
   *
   * El cerrojo es el que ya usa [generar] para toda la emisión, así que tomarlo aquí garantiza que
   * no hay ninguna inferencia viva. Y el `close()` va a [Dispatchers.IO] porque tirar 2,6 GB de
   * memoria nativa tarda, y en el hilo principal eso se siente como que los botones no responden.
   */
  suspend fun soltar() {
    cerrojo.withLock {
      if (engine == null) return@withLock
      withContext(Dispatchers.IO) { liberar() }
    }
  }

  /**
   * Apaga todo, sin esperar a nadie. Primero la conversación, después el motor: al revés se queda
   * memoria nativa colgada.
   *
   * **Solo para cuando el proceso se está muriendo** (`onDestroy`, `onTerminate`). Con la app viva
   * hay que usar [soltar]: esta versión no comprueba si hay una generación en curso.
   */
  fun liberar() {
    cerrarConversacion()
    try {
      engine?.close()
    } catch (e: Exception) {
      Log.e(TAG, "Falló el cierre del motor", e)
    }
    engine = null
    rolActual = null
    _estado.value = EstadoMotor.SinModelo
    Log.i(TAG, "Motor liberado")
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Devuelve la conversación del rol, recreándola salvo que se pida conservar el historial.
   *
   * **Por defecto cada llamada empieza limpia, y es la decisión correcta aquí.** Ninguno de los tres
   * agentes de Masi necesita recordar el turno anterior: el LECTOR convierte una imagen en texto, el
   * ESCUCHA convierte un audio en texto, el TUTOR convierte un error en una pista. Son funciones,
   * no conversaciones.
   *
   * Reutilizar la conversación parecía un ahorro y era una fuga: cada foto dejaba su imagen (~560
   * tokens) y cada grabación su audio (~150–750 tokens) dentro de la caché KV, que crece y
   * **ralentiza cada token siguiente**. Tras cuatro o cinco fragmentos leídos, el contexto estaba
   * lleno de audio viejo, la generación iba al ralentí y acababa por no caber. Recrear cuesta
   * volver a procesar el system prompt —200-400 tokens, y el prefill es rápido y paralelo—, que es
   * un precio ridículo comparado con arrastrar medio libro en memoria.
   */
  private fun prepararConversacion(rol: Rol, conHistorial: Boolean): Conversation {
    val motor = engine ?: error("El motor no está encendido. Llama antes a arrancar().")
    val actual = conversacion
    if (conHistorial && actual != null && rolActual == rol) return actual

    cerrarConversacion()
    val nueva =
      motor.createConversation(
        ConversationConfig(
          systemInstruction = Contents.of(rol.systemPrompt),
          samplerConfig =
            SamplerConfig(topK = rol.topK, topP = rol.topP, temperature = rol.temperatura),
        )
      )
    conversacion = nueva
    rolActual = rol
    Log.d(TAG, "Conversación creada para el rol $rol")
    return nueva
  }

  private fun cerrarConversacion() {
    try {
      conversacion?.close()
    } catch (e: Exception) {
      Log.e(TAG, "Falló el cierre de la conversación", e)
    }
    conversacion = null
  }

  private fun ordenar(
    texto: String,
    imagen: ByteArray?,
    audioWav: ByteArray?,
    orden: OrdenContenido,
  ): List<Content> {
    val partes = mutableListOf<Content>()
    imagen?.let { partes.add(Content.ImageBytes(it)) }
    when (orden) {
      OrdenContenido.GALLERY -> {
        audioWav?.let { partes.add(Content.AudioBytes(it)) }
        if (texto.isNotBlank()) partes.add(Content.Text(texto))
      }
      OrdenContenido.MODEL_CARD -> {
        if (texto.isNotBlank()) partes.add(Content.Text(texto))
        audioWav?.let { partes.add(Content.AudioBytes(it)) }
      }
    }
    return partes
  }

  private fun transmitir(conv: Conversation, contenidos: List<Content>): Flow<String> =
    callbackFlow {
      val callback =
        object : MessageCallback {
          override fun onMessage(message: Message) {
            trySend(message.toString())
          }

          override fun onDone() {
            close()
          }

          override fun onError(throwable: Throwable) {
            if (throwable is CancellationException) {
              close()
            } else {
              Log.e(TAG, "Error durante la generación", throwable)
              close(throwable)
            }
          }
        }

      conv.sendMessageAsync(Contents.of(contenidos), callback, emptyMap())

      awaitClose {
        // Se llega aquí tanto al terminar bien como al cancelar. Cancelar un proceso ya terminado
        // lanza IllegalStateException, y no es un problema.
        try {
          conv.cancelProcess()
        } catch (e: IllegalStateException) {
          // Ya había terminado.
        }
      }
    }
}
