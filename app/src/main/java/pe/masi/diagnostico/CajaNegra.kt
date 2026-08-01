package pe.masi.diagnostico

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

private const val TAG = "MasiCajaNegra"

/**
 * Los pasos peligrosos que se marcan antes de intentarlos.
 *
 * El texto se guarda en disco y se le enseña a un adulto, así que va en castellano llano: quien lo
 * lea puede no saber qué es un encoder de visión.
 */
enum class Paso(val descripcion: String) {
  CARGANDO_MODELO("cargando el modelo en memoria"),
  PRECALENTANDO_VISION("preparando la cámara para el modelo"),
  TOMANDO_FOTO("procesando la foto"),
  LEYENDO_PAGINA("leyendo la página"),
  ESCUCHANDO("escuchando al niño"),
}

/**
 * La última vez que Masi comparó lo escrito con lo que oyó.
 *
 * **Se enseña en Ajustes y no sale del teléfono.** Existe porque hay dos fallos que en pantalla se
 * ven exactamente igual —Masi felicita cuando no debería— y se arreglan de formas opuestas:
 *
 *  - el modelo transcribió lo que el niño dijo de verdad ("cuaderno") y fue la política la que se lo
 *    tragó, o
 *  - el modelo **autocorrigió** y transcribió "libro" porque es lo que esperaba oír, que es el fallo
 *    clásico de cualquier reconocedor de voz guiado por contexto.
 *
 * Sin este dato no hay forma de distinguirlos sin un cable USB y un portátil delante, y el equipo
 * prueba la app en sus propios teléfonos.
 *
 * Vive solo en memoria: se pierde al cerrar la app, y así debe ser.
 */
data class Escuchado(val esperado: String, val transcrito: String, val decision: String)

/** Lo que pasó la vez anterior. */
sealed interface Antecedente {
  data object TodoBien : Antecedente

  /** La app murió mientras hacía esto. Incluye la traza si el fallo fue de Java. */
  data class SeCerroEn(val paso: Paso, val traza: String?) : Antecedente
}

/**
 * Una caja negra para un avión que no lleva radio.
 *
 * **El problema que resuelve:** cuando Masi se cierra en el teléfono de otra persona, hoy no queda
 * absolutamente ningún rastro. No hay reporte de fallos, no hay Play Console —el APK se reparte a
 * mano— y el equipo solo puede decir "no me funciona". Peor todavía: **la mitad de los cierres de
 * esta app son nativos**. El sistema mata el proceso por memoria, o revienta `liblitertlm_jni.so`.
 * Un `Thread.setDefaultUncaughtExceptionHandler` no ve ninguno de esos dos casos, porque no hay
 * excepción de Java que capturar: el proceso simplemente deja de existir.
 *
 * **La técnica que sí funciona son las migas de pan.** Antes de cada operación peligrosa se escribe
 * en un archivo *"estoy a punto de cargar el modelo"*, y al terminar bien se borra. Si al abrir la
 * app la miga sigue ahí, es que la vez anterior murió justo en ese punto. Sobrevive a cualquier
 * forma de morir, incluida la más brutal, porque el dato ya está en disco antes de que pase nada.
 *
 * El coste es un `writeText` de veinte bytes antes de operaciones que tardan segundos. No se nota.
 *
 * Se combina con un manejador de excepciones para los fallos de Java, que añade la traza junto a la
 * miga: entre los dos cubren todo lo que puede pasar.
 */
object CajaNegra {

  private const val ARCHIVO_MIGA = "masi_miga.txt"
  private const val ARCHIVO_TRAZA = "masi_traza.txt"

  /** Se lee UNA vez al arrancar y se guarda: el resto de la app pregunta aquí, no al disco. */
  @Volatile private var antecedente: Antecedente = Antecedente.TodoBien

  /**
   * Lo que pasó la vez anterior. Hay que llamarlo al arrancar, antes de poner ninguna miga nueva.
   *
   * Devuelve el antecedente **y limpia el rastro**: si no se limpiara, un solo cierre marcaría el
   * teléfono para siempre y la app acabaría desaconsejándose a sí misma sin motivo.
   */
  fun revisarYLimpiar(context: Context): Antecedente {
    val miga = archivo(context, ARCHIVO_MIGA)
    val traza = archivo(context, ARCHIVO_TRAZA)

    antecedente =
      if (!miga.exists()) {
        Antecedente.TodoBien
      } else {
        val nombre = runCatching { miga.readText().trim() }.getOrNull()
        val paso = Paso.entries.firstOrNull { it.name == nombre }
        if (paso == null) {
          Antecedente.TodoBien
        } else {
          val detalle = runCatching { traza.takeIf { it.exists() }?.readText() }.getOrNull()
          Log.w(TAG, "La vez anterior la app se cerró: ${paso.descripcion}")
          Antecedente.SeCerroEn(paso, detalle)
        }
      }

    runCatching {
      miga.delete()
      traza.delete()
    }
    return antecedente
  }

  /** Lo último que se supo, sin volver a tocar el disco. */
  fun ultimoAntecedente(): Antecedente = antecedente

  /**
   * La última escucha. **En memoria y nunca en disco**: es la voz de un niño.
   *
   * Deliberadamente fuera de [huella]. El informe se comparte por WhatsApp y ahí no viaja nada que
   * haya dicho o leído el niño; esto solo se mira en la pantalla del aparato donde ocurrió.
   */
  @Volatile
  var ultimaEscucha: Escuchado? = null
    private set

  /** Lo llama [pe.masi.servicios.EvaluadorPronunciacion] al terminar cada evaluación. */
  fun anotarEscucha(esperado: String, transcrito: String, decision: String) {
    ultimaEscucha = Escuchado(esperado, transcrito, decision)
  }

  /** Marca que se va a intentar algo peligroso. Barato: veinte bytes antes de tardar segundos. */
  fun poner(context: Context, paso: Paso) {
    runCatching { archivo(context, ARCHIVO_MIGA).writeText(paso.name) }
      .onFailure { Log.w(TAG, "No se pudo poner la miga", it) }
  }

  /**
   * Salió bien: se retira la miga.
   *
   * También se llama al pasar la app a segundo plano. Un cierre ordenado del sistema mientras Masi
   * no está en pantalla no es un fallo, y dejar la miga puesta produciría un aviso falso al volver.
   */
  fun quitar(context: Context) {
    runCatching { archivo(context, ARCHIVO_MIGA).delete() }
  }

  /** Guarda la traza de una excepción de Java junto a la miga. La escribe el manejador global. */
  fun anotarTraza(context: Context, texto: String) {
    runCatching { archivo(context, ARCHIVO_TRAZA).writeText(texto.take(4_000)) }
  }

  /**
   * La ficha del teléfono, para que alguien la mande por WhatsApp.
   *
   * Está pensada para leerse en un móvil y pegarse en un chat, así que no lleva jerga ni formato
   * raro. **Ninguna línea contiene datos del niño**: ni palabras practicadas, ni cuentos, ni audio.
   * Solo el aparato y qué le pasó a la app.
   */
  fun huella(context: Context, backend: String, perfil: String): String {
    val memoria = memoria(context)
    val abis = Build.SUPPORTED_ABIS.joinToString(", ")
    val ultimo =
      when (val a = antecedente) {
        is Antecedente.TodoBien -> "sin cierres"
        is Antecedente.SeCerroEn -> "se cerró ${a.paso.descripcion}"
      }

    return buildString {
      appendLine("Masi · informe del teléfono")
      appendLine("Teléfono: ${Build.MANUFACTURER} ${Build.MODEL}")
      appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
      appendLine("Procesador: $abis")
      appendLine("Memoria: $memoria")
      appendLine("Motor: $backend")
      appendLine("Modo: $perfil")
      appendLine("Última vez: $ultimo")
      (antecedente as? Antecedente.SeCerroEn)?.traza?.let {
        appendLine()
        appendLine("Detalle:")
        appendLine(it.lineSequence().take(12).joinToString("\n"))
      }
    }
  }

  /** Memoria libre y total, en el formato que se enseña. */
  fun memoria(context: Context): String {
    val info = ActivityManager.MemoryInfo()
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    am?.getMemoryInfo(info) ?: return "no se pudo medir"
    val libres = info.availMem / 1_073_741_824.0
    val total = info.totalMem / 1_073_741_824.0
    val baja = if (info.lowMemory) " · el sistema va justo" else ""
    return "%.1f GB libres de %.1f GB%s".format(libres, total, baja)
  }

  /**
   * En `cacheDir` a propósito y no en `filesDir`.
   *
   * Si el sistema borra la caché por falta de espacio, lo peor que pasa es perder un diagnóstico.
   * Ocupar espacio permanente para esto sería peor negocio en un teléfono que ya va apurado.
   */
  private fun archivo(context: Context, nombre: String) = File(context.cacheDir, nombre)
}
