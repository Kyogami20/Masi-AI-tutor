package pe.masi

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pe.masi.datos.MasiDatabase
import pe.masi.diagnostico.Antecedente
import pe.masi.diagnostico.CajaNegra
import pe.masi.datos.Preferencias
import pe.masi.media.VozMasi
import pe.masi.motor.MotorMasi
import pe.masi.servicios.CargadorDePictogramas
import pe.masi.servicios.CuentistaService
import pe.masi.servicios.EnriquecedorService
import pe.masi.servicios.EscuchaService
import pe.masi.servicios.EvaluadorPronunciacion
import pe.masi.servicios.LectorService
import pe.masi.servicios.RepasoService
import pe.masi.servicios.TutorService

/**
 * Las piezas de Masi, creadas una vez y compartidas.
 *
 * Sin framework de inyección: son ocho objetos y un grafo que cabe en la cabeza. Meter Hilt aquí
 * sería añadir compilación anotada, tiempo de build y un manual entero a cambio de nada.
 */
class Contenedor(private val context: Context) {

  /**
   * El motor vive aquí y no en un ViewModel a propósito: cargar el modelo cuesta segundos y varios
   * gigas de RAM, y no puede repetirse cada vez que la pantalla rota.
   */
  val motor = MotorMasi(context)

  val voz = VozMasi(context)
  val preferencias = Preferencias(context)

  private val db = MasiDatabase.obtener(context)
  val repaso = RepasoService(db.tarjetaDao(), db.cuentoDao())

  val lector by lazy { LectorService(motor) }
  val escucha by lazy { EscuchaService(motor) }
  val tutor by lazy { TutorService(motor) }

  /** El bucle de evaluación que comparten la pantalla de Escuchar y la de Practicar. */
  val evaluador by lazy { EvaluadorPronunciacion(escucha, tutor) }

  val cuentista by lazy { CuentistaService(motor, repaso) }

  /** El banco de pictogramas vive en assets: se lee una vez y se queda en memoria. */
  val pictogramas by lazy { CargadorDePictogramas.cargar(context) }

  /**
   * El servicio agéntico de Masi: el modelo busca el dibujo, y si no lo encuentra razona con qué
   * concepto emparentado volver a buscar. Ver [EnriquecedorService].
   */
  val enriquecedor by lazy { EnriquecedorService(motor, pictogramas) }

  fun liberar() {
    motor.liberar()
    voz.liberar()
  }

  /**
   * Suelta el modelo pero deja en pie todo lo demás: base de datos, ajustes y voz siguen vivos.
   *
   * Espera a que no haya ninguna generación en curso. Ver [MotorMasi.soltar].
   */
  suspend fun soltarModelo() {
    motor.soltar()
  }
}

/**
 * Cuánto se espera, con la app en segundo plano, antes de soltar el modelo.
 *
 * **Este número está medido en el teléfono, no elegido a ojo.** La primera versión esperaba 30 s
 * suponiendo que había margen de sobra; el logcat del Redmi Note 11 Pro+ dijo otra cosa:
 *
 * ```
 * 17:13:32  la app pasa a segundo plano
 * 17:13:41  ActivityManager: Killing pe.masi (adj 501): MiuiMemoryService(previous-active)
 * ```
 *
 * Nueve segundos. MIUI no tolera un proceso de 3,3 GB en segundo plano, así que la gracia de 30 s
 * no llegaba a cumplirse **nunca**: el proceso moría entero antes, y volver costaba un arranque en
 * frío con el recorrido perdido.
 *
 * Con cinco segundos el modelo se suelta a tiempo, el proceso baja a unos 200 MB y deja de ser un
 * objetivo. Volver cuesta recargar el motor (~10 s, tapado por la animación) pero se conserva
 * dónde estaba el niño: la página que fotografió y el fragmento por el que iba.
 *
 * El precio es que un cambio de app de tres segundos también paga la recarga. Es el precio
 * correcto: sin esto, ese mismo cambio de app costaba la sesión entera.
 */
private const val GRACIA_SEGUNDO_PLANO_MS = 5_000L

class MasiApplication : Application() {

  lateinit var contenedor: Contenedor
    private set

  /**
   * Un scope propio de la aplicación. No puede ser el de un ViewModel: justamente lo que hace es
   * sobrevivir a que las pantallas desaparezcan.
   *
   * En [Dispatchers.Default], no en el principal: lo único que corre aquí es soltar el motor, y eso
   * bloquea mientras se destruyen 2,6 GB de memoria nativa.
   */
  private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var soltadoPendiente: Job? = null

  /**
   * Si el motor se soltó por estar en segundo plano, hay que volver a encenderlo al regresar.
   *
   * Se distingue de "nunca se ha encendido" a propósito: en el primer arranque quien enciende es la
   * pantalla de Bienvenida, que además enseña la animación mientras tanto. Aquí solo se repone lo
   * que esta clase quitó.
   */
  private var motorSoltado = false

  override fun onCreate() {
    super.onCreate()

    // LO PRIMERO, antes de construir nada: leer si la vez anterior la app murió y dónde.
    //
    // Tiene que ir aquí porque el resto del arranque ya empieza a poner migas nuevas, y si se
    // leyera después se leería la de esta ejecución en vez de la de la anterior.
    val antecedente = CajaNegra.revisarYLimpiar(this)
    if (antecedente is Antecedente.SeCerroEn) {
      Log.w(TAG, "Arranque tras un cierre: ${antecedente.paso.descripcion}")
    }
    instalarManejadorDeFallos()

    contenedor = Contenedor(this)

    ProcessLifecycleOwner.get()
      .lifecycle
      .addObserver(
        object : DefaultLifecycleObserver {
          override fun onStart(owner: LifecycleOwner) {
            // Volvió antes de que se cumpliera la gracia: el modelo sigue caliente.
            soltadoPendiente?.cancel()
            soltadoPendiente = null

            // Y si ya se había soltado, se repone ahora en vez de esperar a que el niño pulse algo.
            // El motor se reenciende solo al pedirle la primera generación, pero entonces esos ~10 s
            // caen enteros dentro de "Masi está mirando tu libro…", justo cuando ya está esperando.
            // Traerlos aquí los esconde detrás de los segundos que tarda en apuntar a la página.
            if (motorSoltado) {
              motorSoltado = false
              alcance.launch { contenedor.motor.arrancar() }
            }
          }

          /**
           * En segundo plano el modelo no hace nada, y son ~2,6 GB de memoria nativa ocupada.
           *
           * Mantenerlo cargado mientras el niño usa otra app es exactamente lo que hace que Android
           * mate el proceso, y entonces volver cuesta un arranque en frío completo en vez de una
           * recarga. Soltarlo a propósito es más barato que que te lo quiten.
           */
          override fun onStop(owner: LifecycleOwner) {
            // Irse a segundo plano no es un fallo. Si el sistema cierra la app estando fuera de
            // pantalla, dejar la miga puesta produciría un aviso falso al volver a abrirla.
            CajaNegra.quitar(this@MasiApplication)
            if (!contenedor.motor.estaListo) return
            soltadoPendiente =
              alcance.launch {
                delay(GRACIA_SEGUNDO_PLANO_MS)
                contenedor.soltarModelo()
                motorSoltado = true
                Log.i(TAG, "Modelo liberado tras ${GRACIA_SEGUNDO_PLANO_MS / 1000} s en segundo plano")
              }
          }

          // El recolector de basura no libera memoria nativa de C++. Si el proceso se va sin cerrar
          // el motor, esos gigas se quedan colgados hasta que Android mate el proceso entero.
          override fun onDestroy(owner: LifecycleOwner) {
            soltadoPendiente?.cancel()
            contenedor.liberar()
          }
        }
      )
  }

  // Aquí hubo un `onTrimMemory` que soltaba el modelo al detectar presión de memoria. Era una idea
  // equivocada por dos motivos, y tiró la app en el teléfono:
  //
  //  1. Los niveles `TRIM_MEMORY_RUNNING_*` llegan con la app **en primer plano**, y llegan
  //     precisamente cuando Masi está trabajando: un modelo de 2,6 GB residente ES la presión de
  //     memoria del sistema. Reaccionar cerrándolo era garantizar el cierre en el peor momento
  //     posible, a mitad de mirar la foto o de escuchar al niño.
  //  2. Cerraba el motor sin comprobar si había una inferencia viva, y desde el hilo principal.
  //
  // El resultado fue `SIGSEGV` en `liblitertlm_jni.so` y la app cerrándose en seco. Si algún día
  // hace falta responder a la memoria, la única vía segura es `motor.soltar()` desde una corrutina,
  // nunca `liberar()` a pelo. Android ya sabe matar el proceso si de verdad necesita la RAM, y eso
  // es más barato que un fallo nativo.

  /**
   * Guarda la traza de los fallos de Java antes de que el proceso muera.
   *
   * Cubre solo la mitad del problema, y conviene tenerlo claro: **los cierres por memoria y los
   * fallos nativos de `liblitertlm_jni.so` no pasan por aquí**, porque no hay excepción de Java que
   * capturar. Para esos está la miga de pan de [CajaNegra], que ya está escrita en disco antes de
   * que nada ocurra.
   *
   * Se encadena al manejador anterior en vez de sustituirlo: quitarle a Android su gestión de
   * cierres dejaría a la app colgada en vez de cerrarse, que es peor.
   */
  private fun instalarManejadorDeFallos() {
    val anterior = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { hilo, fallo ->
      runCatching {
        CajaNegra.anotarTraza(this, buildString {
          appendLine("${fallo::class.java.simpleName}: ${fallo.message}")
          fallo.stackTrace.take(8).forEach { appendLine("  en $it") }
        })
      }
      anterior?.uncaughtException(hilo, fallo)
    }
  }

  override fun onTerminate() {
    contenedor.liberar()
    super.onTerminate()
  }

  private companion object {
    const val TAG = "MasiApp"
  }
}
