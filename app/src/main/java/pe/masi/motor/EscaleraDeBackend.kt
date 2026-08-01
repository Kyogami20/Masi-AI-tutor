package pe.masi.motor

/**
 * Cómo se reparte el trabajo entre la GPU y el procesador normal.
 *
 * **El texto y la visión se configuran por separado, y ese es el hallazgo que resuelve el caso más
 * difícil que ha tenido este proyecto.** Google AI Edge Gallery expone dos ajustes independientes
 * (`ACCELERATOR` y `VISION_ACCELERATOR`), y no es un capricho suyo: hay teléfonos donde la GPU corre
 * el modelo de texto perfectamente y **solo falla con el encoder de visión**.
 *
 * Le pasó a un moto g54 5G del equipo. El modelo cargaba, la app parecía sana, y todas las fotos
 * fallaban con "no se entiende la página". En Gallery le funcionaba poniendo CPU. La diferencia
 * entre [GPU_VISION_CPU] y [TODO_CPU] es que el primero conserva la velocidad del texto y solo
 * mueve lo que de verdad está roto.
 *
 * El audio va **siempre** en CPU en los tres peldaños. No es una preferencia: es una restricción del
 * runtime para Gemma, y está así también en el código de Gallery.
 */
enum class Peldano(val texto: String, val descripcion: String) {
  /** Lo más rápido, y lo que funciona en la mayoría de teléfonos. */
  TODO_GPU("GPU", "rápido"),

  /**
   * Texto en GPU, visión en CPU.
   *
   * El peldaño que arregla el moto g54. Las fotos tardan más; lo demás va igual de rápido.
   */
  GPU_VISION_CPU("GPU + visión en CPU", "compatible"),

  /** Todo en el procesador normal. Notablemente más lento, pero funciona casi en cualquier sitio. */
  TODO_CPU("CPU", "lento pero seguro");

  val visionEnGpu: Boolean
    get() = this == TODO_GPU

  val textoEnGpu: Boolean
    get() = this != TODO_CPU

  /** El siguiente peldaño hacia abajo, o null si ya no queda ninguno. */
  fun siguiente(): Peldano? = entries.getOrNull(ordinal + 1)
}

/** Lo que un adulto elige en Ajustes. */
enum class PreferenciaDeMotor {
  /** Empieza arriba y baja solo cuando algo falla. Lo normal. */
  AUTOMATICO,
  FORZAR_GPU,
  FORZAR_VISION_CPU,
  FORZAR_CPU;

  /** El peldaño fijado a mano, o null si manda el automático. */
  fun peldanoFijo(): Peldano? =
    when (this) {
      AUTOMATICO -> null
      FORZAR_GPU -> Peldano.TODO_GPU
      FORZAR_VISION_CPU -> Peldano.GPU_VISION_CPU
      FORZAR_CPU -> Peldano.TODO_CPU
    }
}

/**
 * Decide cuándo hay que bajar de peldaño.
 *
 * Kotlin puro y sin Android para poder probarlo: la lógica de cuándo se degrada un teléfono no se
 * puede depender de tenerlo delante.
 *
 * **La distinción que hace que esto funcione** es entre los dos resultados que ya devuelve
 * `LectorService` y que hasta ahora se tiraban al mismo sitio:
 *
 *  - Una foto mala (`NoSeEntiende`) es el modelo respondiendo bien a una imagen borrosa. **No dice
 *    nada del backend** y no debe degradar nada; si no, un niño con mal pulso acabaría con la app en
 *    modo lento sin motivo.
 *  - Un fallo (`Fallo`) es agotar el tiempo o devolver algo imposible de parsear dos veces
 *    seguidas. **Esa es la firma de un backend que no responde.**
 *
 * Dos seguidos, no uno: una vez puede ser mala suerte, dos es un patrón.
 */
class VigilanteDeBackend(
  private var peldanoActual: Peldano = Peldano.TODO_GPU,
  private val fallosParaBajar: Int = FALLOS_PARA_BAJAR,
) {
  private var fallosSeguidos = 0

  val peldano: Peldano
    get() = peldanoActual

  /** El LECTOR devolvió texto: lo que hubiera pasado antes ya no cuenta. */
  fun anotarExito() {
    fallosSeguidos = 0
  }

  /** La foto estaba mal. No es culpa del backend y no acerca la degradación. */
  fun anotarFotoIlegible() {
    fallosSeguidos = 0
  }

  /**
   * El motor no respondió. Devuelve el peldaño nuevo si toca bajar, o null si no.
   *
   * Al bajar se reinicia la cuenta: el peldaño nuevo merece sus propios dos intentos antes de que se
   * le culpe de nada.
   */
  fun anotarFallo(): Peldano? {
    fallosSeguidos++
    if (fallosSeguidos < fallosParaBajar) return null

    val siguiente = peldanoActual.siguiente() ?: return null
    peldanoActual = siguiente
    fallosSeguidos = 0
    return siguiente
  }

  /**
   * Fija el peldaño desde fuera: lo recordado de una sesión anterior, o lo que eligió un adulto.
   *
   * **No se vuelve a subir solo.** Si en este teléfono la GPU no sirve, no va a servir mañana, y
   * reintentarla en cada arranque solo produce dos fotos fallidas antes de volver a bajar.
   */
  fun fijar(peldano: Peldano) {
    peldanoActual = peldano
    fallosSeguidos = 0
  }

  private companion object {
    const val FALLOS_PARA_BAJAR = 2
  }
}
