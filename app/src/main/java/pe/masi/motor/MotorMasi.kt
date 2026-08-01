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
import com.google.ai.edge.litertlm.ToolProvider
import pe.masi.diagnostico.CajaNegra
import pe.masi.diagnostico.Paso
import java.io.ByteArrayOutputStream
import kotlin.random.Random
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
 * La semilla de los roles que deben ser reproducibles: transcribir dos veces debe dar lo mismo.
 *
 * **Vale exactamente lo que la librería pone por defecto**, y eso está comprobado, no supuesto:
 * el constructor sintético de `SamplerConfig` en litertlm-android 0.11.0 carga un `iconst_0` para
 * este parámetro. Escribirlo aquí no cambia ni un token; solo deja el valor a la vista.
 *
 * La comprobación no es un detalle ocioso. Al arreglar los cuentos idénticos se añadió `seed` a
 * todos los roles, y quedó la sospecha razonable de que eso hubiera alterado también al ESCUCHA y
 * fuera la causa de que empezara a felicitar por errores graves. No lo era —el fallo estaba en
 * [pe.masi.servicios.EvaluadorPronunciacion] desde el primer día— y esto lo descarta con evidencia
 * en vez de con un argumento.
 */
private const val SEMILLA_FIJA = 0

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
  /**
   * Si cada llamada debe dar un resultado distinto.
   *
   * **`SamplerConfig` lleva una semilla, y por defecto es fija.** Con la misma semilla y el mismo
   * prompt, el modelo devuelve exactamente el mismo texto, palabra por palabra, por muy alta que
   * sea la temperatura. Se descubrió generando dos cuentos seguidos con las mismas tres palabras:
   * salieron idénticos.
   *
   * Afecta también al TUTOR, aunque no se hubiera notado: la misma palabra fallada daba siempre la
   * misma pista. Para el LECTOR y el ESCUCHA, en cambio, la semilla fija es lo correcto —
   * transcribir dos veces lo mismo debe dar lo mismo.
   */
  val variado: Boolean = false,
) {
  LECTOR(Prompts.LECTOR, temperatura = 0.2, topK = 40, topP = 0.95),
  ESCUCHA(Prompts.ESCUCHA, temperatura = 0.1, topK = 10, topP = 0.95),
  TUTOR(Prompts.TUTOR, temperatura = 0.7, topK = 64, topP = 0.95, variado = true),

  /**
   * Escribe cuentos con las palabras que al niño le cuestan. El único rol con herramientas.
   *
   * Temperatura alta: dos cuentos seguidos con las mismas palabras tienen que ser distintos, o el
   * niño deja de leerlos. Es lo contrario del LECTOR, donde creatividad significa alucinación.
   */
  /**
   * Temperatura alta, como el TUTOR: dos cuentos seguidos con las mismas palabras tienen que ser
   * distintos o el niño deja de leerlos.
   *
   * Estuvo en 0.6 mientras este rol usaba herramientas, porque el function calling quiere
   * determinismo. Al volver a ser una generación normal, la prosa manda.
   */
  CUENTISTA(Prompts.CUENTISTA, temperatura = 0.9, topK = 64, topP = 0.95, variado = true),

  /**
   * Explica una palabra y le busca un dibujo. **El único rol con herramientas.**
   *
   * Temperatura baja, al contrario que el CUENTISTA: aquí no queremos prosa variada, queremos que
   * siga la secuencia de llamadas y que la definición sea precisa. Es la tensión que se descubrió
   * probando el cuento con herramientas, resuelta al revés porque el objetivo es el contrario.
   */
  ENRIQUECEDOR(Prompts.ENRIQUECEDOR, temperatura = 0.4, topK = 40, topP = 0.95),
}

sealed interface EstadoMotor {
  /** Todavía no hay archivo de modelo en el dispositivo. */
  data object SinModelo : EstadoMotor

  /** `engine.initialize()` en curso. Puede tardar hasta 10 s: hay que taparlo con animación. */
  data object Cargando : EstadoMotor

  /**
   * @param peldano el reparto GPU/CPU con el que de verdad arrancó.
   *
   * Antes aquí había un `aceleracionGpu` fijado a `true` a mano, que mentía: aunque el motor cayera
   * a CPU, el estado seguía diciendo GPU. Eso le quita a quien depura el único dato que importa.
   */
  data class Listo(val peldano: Peldano, val mtp: Boolean, val perfil: PerfilDispositivo) :
    EstadoMotor

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

  /**
   * Quién decide el reparto GPU/CPU y cuándo bajar de peldaño.
   *
   * Vive aquí y no en un servicio porque tiene que sobrevivir a que se suelte y se recargue el
   * motor: si se reiniciara con cada recarga, el teléfono volvería a intentar la GPU rota una y otra
   * vez.
   */
  private val vigilante = VigilanteDeBackend()

  /** El presupuesto con el que se cargó. Lo consultan la cámara y el ciclo de vida. */
  @Volatile
  var presupuesto: PresupuestoMasi = PresupuestoMasi.de(PerfilDispositivo.AJUSTADO)
    private set

  val peldanoActual: Peldano
    get() = vigilante.peldano

  /** Fija el reparto a mano, o lo restaura de lo aprendido. Lo llaman Ajustes y el arranque. */
  fun fijarPeldano(peldano: Peldano) = vigilante.fijar(peldano)

  fun anotarLecturaCorrecta() = vigilante.anotarExito()

  fun anotarFotoIlegible() = vigilante.anotarFotoIlegible()

  /**
   * El motor no respondió. Si toca bajar de peldaño, **suelta el motor y lo recarga con el nuevo
   * reparto**, y devuelve el peldaño al que se bajó.
   *
   * Este es el arreglo del moto g54, y la diferencia con el reintento que ya existía al arrancar:
   * en ese teléfono `initialize()` **tiene éxito**, así que el reintento de arranque no se dispara
   * jamás. El fallo solo aparece con la primera foto, cuando entra en juego el encoder de visión, y
   * para entonces la app ya se ha dado por buena.
   *
   * La recarga usa `soltar()`, que espera al cerrojo: cerrar el motor a mitad de una inferencia es
   * el `SIGSEGV` que ya costó una ronda entera.
   */
  suspend fun anotarFalloDelModelo(): Peldano? {
    val nuevo = vigilante.anotarFallo() ?: return null
    Log.w(TAG, "Dos fallos seguidos del modelo: se baja al peldaño $nuevo y se recarga")
    soltar()
    arrancar()
    return nuevo
  }

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

      // El perfil se mide AHORA, no al abrir la app: si acaban de cerrarse otras aplicaciones, la
      // respuesta cambia a mejor.
      val perfil = RecursosDispositivo.perfil(context)
      presupuesto = PresupuestoMasi.de(perfil)
      _estado.value = EstadoMotor.Cargando

      withContext(Dispatchers.IO) {
        // El archivo dice si trae el modelo borrador para decodificación especulativa (MTP).
        // Activarlo a ciegas en un archivo que no lo trae hace fallar la inicialización.
        val soportaMtp =
          try {
            Capabilities(modelo.ruta).use { it.hasSpeculativeDecodingSupport() }
          } catch (e: Exception) {
            Log.w(TAG, "No se pudo inspeccionar el modelo; se asume sin MTP", e)
            false
          }

        // Se prueba desde el peldaño actual hacia abajo. Si la GPU no arranca, se cae sola a la
        // combinación siguiente en vez de rendirse, que es lo que hacía antes: un `catch` que ponía
        // Error y dejaba al niño sin recorrido ninguno.
        var candidato: Peldano? = vigilante.peldano
        while (candidato != null) {
          if (intentarArrancar(modelo.ruta, candidato, soportaMtp, perfil)) {
            vigilante.fijar(candidato)
            return@withContext true
          }
          Log.w(TAG, "El peldaño $candidato no arrancó; se baja uno")
          candidato = candidato.siguiente()
        }
        _estado.value = EstadoMotor.Error("No se pudo encender el motor en este teléfono")
        false
      }
    }

  /**
   * Un intento con un reparto GPU/CPU concreto.
   *
   * Los fallos NATIVOS —el sistema mata el proceso, o revienta el `.so`— no pasan por este `catch`
   * ni por ningún otro. Para esos está la miga de pan que se pone justo antes.
   */
  @OptIn(ExperimentalApi::class)
  private fun intentarArrancar(
    ruta: String,
    peldano: Peldano,
    soportaMtp: Boolean,
    perfil: PerfilDispositivo,
  ): Boolean =
    try {
      CajaNegra.poner(context, Paso.CARGANDO_MODELO)
      val config =
        EngineConfig(
          modelPath = ruta,
          backend = if (peldano.textoEnGpu) Backend.GPU() else Backend.CPU(),
          // La visión se configura APARTE del texto, y ahí está la clave: hay teléfonos donde la
          // GPU corre el texto perfectamente y solo rompe con el encoder de visión.
          visionBackend = if (peldano.visionEnGpu) Backend.GPU() else Backend.CPU(),
          // El audio va siempre en CPU: restricción del runtime para Gemma, no preferencia.
          audioBackend = Backend.CPU(),
          maxNumTokens = presupuesto.maxTokens,
          cacheDir = context.cacheDir.absolutePath,
        )

      ExperimentalFlags.enableSpeculativeDecoding = soportaMtp
      val nuevo = Engine(config)
      nuevo.initialize()
      ExperimentalFlags.enableSpeculativeDecoding = false
      CajaNegra.quitar(context)

      engine = nuevo
      _estado.value = EstadoMotor.Listo(peldano = peldano, mtp = soportaMtp, perfil = perfil)
      Log.i(
        TAG,
        "Motor listo. peldaño=$peldano, MTP=$soportaMtp, " +
          "maxTokens=${presupuesto.maxTokens}, perfil=$perfil",
      )
      true
    } catch (e: Throwable) {
      // `Throwable` y no `Exception`: un `OutOfMemoryError` es un `Error` y se escaparía.
      Log.e(TAG, "Falló la inicialización en el peldaño $peldano", e)
      ExperimentalFlags.enableSpeculativeDecoding = false
      // Un initialize() a medias deja memoria nativa reservada. Cerrarlo antes de probar el
      // siguiente peldaño evita cargar dos veces el modelo en un teléfono que ya va justo.
      runCatching { engine?.close() }
      engine = null
      false
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
    herramientas: List<ToolProvider> = emptyList(),
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
      val conv = prepararConversacion(rol, conHistorial, herramientas)
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
    herramientas: List<ToolProvider> = emptyList(),
  ): String {
    val sb = StringBuilder()
    generar(rol, texto, imagen, audioWav, orden, conHistorial, herramientas).collect { sb.append(it) }
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
    // En un teléfono apretado esto es contraproducente: provoca un SEGUNDO pico de memoria a los
    // pocos segundos de haber cargado el modelo, justo cuando el sistema está peor. Es preferible
    // que la primera foto tarde 20 s a que la app se cierre antes de llegar a tomarla.
    if (!presupuesto.precalentarVision) {
      Log.i(TAG, "Perfil ${presupuesto.perfil}: se omite el precalentado de visión")
      return
    }
    try {
      CajaNegra.poner(context, Paso.PRECALENTANDO_VISION)
      val lienzo =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
      val png = ByteArrayOutputStream().also { lienzo.compress(Bitmap.CompressFormat.PNG, 100, it) }
      val respuesta =
        withTimeoutOrNull(90_000L) {
          generarCompleto(rol = Rol.LECTOR, texto = "Lee esta página.", imagen = png.toByteArray())
        }
      CajaNegra.quitar(context)
      Log.i(TAG, "Encoder de visión precalentado (respuesta: ${respuesta?.take(40)})")
    } catch (e: Throwable) {
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
  @OptIn(ExperimentalApi::class)
  private fun prepararConversacion(
    rol: Rol,
    conHistorial: Boolean,
    herramientas: List<ToolProvider> = emptyList(),
  ): Conversation {
    val motor = engine ?: error("El motor no está encendido. Llama antes a arrancar().")
    val actual = conversacion
    // Con herramientas nunca se reutiliza: cada bucle agéntico empieza limpio.
    if (conHistorial && herramientas.isEmpty() && actual != null && rolActual == rol) return actual

    cerrarConversacion()

    // Decodificación restringida: el runtime limita la gramática de salida para que la llamada a
    // herramienta sea válida por construcción. Sin esto habría que parsear texto libre, que es
    // justo el problema frágil que el LECTOR ya tiene con su JSON.
    //
    // `ExperimentalFlags` es un singleton MUTABLE GLOBAL, así que este vaivén sería una carrera en
    // una app con varias generaciones a la vez. Aquí es seguro porque el `cerrojo` serializa toda
    // creación de conversación: nadie más puede estar entre estas dos líneas.
    val conHerramientas = herramientas.isNotEmpty()
    if (conHerramientas) ExperimentalFlags.enableConversationConstrainedDecoding = true
    val nueva =
      try {
        motor.createConversation(
          ConversationConfig(
            systemInstruction = Contents.of(rol.systemPrompt),
            samplerConfig =
              SamplerConfig(
                topK = rol.topK,
                topP = rol.topP,
                temperature = rol.temperatura,
                // Ver [Rol.variado]: sin esto, dos cuentos seguidos salen idénticos.
                // Positiva a propósito: `Random.nextInt()` devuelve también negativos, y no está
                // documentado qué hace la capa nativa con una semilla negativa.
                seed = if (rol.variado) Random.nextInt(1, Int.MAX_VALUE) else SEMILLA_FIJA,
              ),
            tools = herramientas,
          )
        )
      } finally {
        if (conHerramientas) ExperimentalFlags.enableConversationConstrainedDecoding = false
      }
    conversacion = nueva
    rolActual = rol
    Log.d(
      TAG,
      "Conversación creada para el rol $rol (herramientas: ${herramientas.size}, " +
        "semilla=${if (rol.variado) "aleatoria" else "fija"}, temp=${rol.temperatura})",
    )
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
