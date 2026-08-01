package pe.masi.motor

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet

/**
 * Las dos reglas de las herramientas de Masi. Romper cualquiera de las dos cuelga la app.
 *
 * Masi usa el **function calling nativo de LiteRT-LM 0.11.0**: las herramientas se declaran con las
 * anotaciones `@Tool` y `@ToolParam` de la librería, que genera el JSON Schema por reflexión y se lo
 * pasa al runtime C++; el runtime lo renderiza dentro del chat template del propio Gemma 4, aplica
 * *constrained decoding* para que la llamada sea sintácticamente válida por construcción, y ejecuta
 * el bucle modelo → herramienta → modelo por su cuenta (`automaticToolCalling`, con un tope de 25
 * llamadas recursivas). **Aquí no se parsea la salida del modelo con expresiones regulares.**
 *
 * Que el bucle lo ejecute la librería es cómodo, pero tiene una consecuencia que hay que tener muy
 * presente: **el código de una herramienta corre dentro de la llamada nativa**, en el hilo del
 * motor, en mitad de una generación. De ahí salen las dos reglas.
 *
 * ---
 *
 * ## Regla 1: una herramienta NUNCA invoca al motor
 *
 * [MotorMasi.generar] mantiene tomado el `cerrojo` durante toda la emisión, y las herramientas se
 * ejecutan mientras ese cerrojo está tomado. Un `Mutex` de Kotlin **no es reentrante**, así que una
 * herramienta que llamara a `generar()`, `generarCompleto()`, `arrancar()` o `soltar()` se quedaría
 * esperando un cerrojo que solo se libera cuando ella termine. Bloqueo mutuo, y la app se queda
 * colgada sin excepción ni traza que mirar.
 *
 * Si hace falta otra pasada del modelo, **no se pide desde aquí**: se devuelve el dato y se deja que
 * el runtime decida. Ese es justamente su trabajo.
 *
 * ## Regla 2: una herramienta no toca Room ni el disco
 *
 * Lee de una **instantánea inmutable** capturada antes de arrancar la generación, y las escrituras
 * se **acumulan** para aplicarlas después. Dos motivos:
 *
 *  - Una consulta a Room es `suspend`; llamarla desde una herramienta obliga a `runBlocking` sobre
 *    el hilo de callback nativo, que es exactamente donde no conviene bloquear.
 *  - Escribir a mitad de la generación deja la base en un estado que depende de si el modelo
 *    terminó o no. Si el bucle se corta a la mitad, quedan cuentos sin título o tarjetas huérfanas.
 *
 * Es el patrón "proponer → ejecutar" que Google AI Edge Gallery usa en Mobile Actions: la función
 * `@Tool` solo acumula en una lista (`curActions`) y el efecto real ocurre después, al terminar la
 * generación (`performAction` dentro de `onProcessDone`). Como efecto secundario deja el hueco
 * natural para que un adulto confirme antes de que nada se guarde.
 *
 * ---
 *
 * ## Qué SÍ puede hacer una herramienta
 *
 *  - Leer de una estructura en memoria que se le pasó al construirla.
 *  - Calcular en Kotlin puro y determinista. **Este es el uso más valioso**: comprobar de verdad si
 *    un texto contiene unas palabras es algo que el modelo hace mal y el código hace perfecto.
 *  - Acumular una propuesta en un recolector.
 *  - Devolver un `Map<String, Any>`, que la librería serializa y devuelve al modelo sola.
 *
 * ## Nombres
 *
 * `ExperimentalFlags.convertCamelToSnakeCaseInToolDescription` está activo por defecto, así que un
 * método `fun comprobarPalabras(...)` se le presenta al modelo como `comprobar_palabras`. Los
 * métodos se escriben en camelCase, como el resto del proyecto, y el prompt del rol —si menciona
 * alguna herramienta— tiene que usar el nombre en snake_case.
 */
object ReglasDeHerramientas {

  /**
   * Comprueba que las herramientas de verdad se ven por reflexión, y lo escribe en el log.
   *
   * **Esto no es paranoia, es una cicatriz.** El fallo más caro de esta parte no da ningún error:
   * si R8 renombra los métodos anotados con `@Tool`, la librería construye un esquema con nombres
   * tipo `a(b)`, la conversación se crea igual —el log dice "herramientas: 1"— y el modelo
   * simplemente no ve nada que pueda llamar y se pone a conversar. Se confunde con un problema de
   * prompt y se pierden rondas enteras de pruebas en el teléfono.
   *
   * Si en el log no aparecen los nombres reales con sus parámetros, el problema NO es el prompt:
   * son las reglas de ProGuard (ver `app/proguard-rules.pro`).
   */
  fun comprobar(herramientas: ToolSet) {
    val metodos = herramientas.javaClass.declaredMethods.filter { it.isAnnotationPresent(Tool::class.java) }
    if (metodos.isEmpty()) {
      Log.e(
        "MasiMotor",
        "SIN HERRAMIENTAS VISIBLES en ${herramientas.javaClass.name}. R8 se llevó por delante los " +
          "métodos o las anotaciones: revisa proguard-rules.pro. El modelo no va a llamar a nada.",
      )
      return
    }
    for (m in metodos) {
      val parametros = m.parameters.joinToString(", ") { "${it.name}: ${it.type.simpleName}" }
      Log.d("MasiMotor", "  @Tool ${m.name}($parametros)")
    }
  }
}
