package pe.masi.motor

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import pe.masi.servicios.BancoDePictogramas

/** Lo que el ENRIQUECEDOR propuso para una tarjeta. En memoria; se escribe al terminar. */
class RecolectorDeTarjeta {
  var definicion: String? = null
    private set

  var ejemplo: String? = null
    private set

  /** El archivo del pictograma elegido, si encontró alguno. */
  var pictograma: String? = null
    private set

  /** El concepto con el que acertó. Interesa: revela cómo razonó el modelo. */
  var conceptoElegido: String? = null
    private set

  /** Todos los conceptos que probó, en orden. Es la traza del razonamiento. */
  val intentos = mutableListOf<String>()

  fun anotarIntento(concepto: String, encontrado: Boolean, archivo: String?) {
    intentos.add(if (encontrado) "$concepto ✓" else "$concepto ✗")
    if (encontrado && pictograma == null) {
      pictograma = archivo
      conceptoElegido = concepto
    }
  }

  fun proponer(definicion: String, ejemplo: String) {
    this.definicion = definicion
    this.ejemplo = ejemplo
  }

  val hayPropuesta: Boolean
    get() = !definicion.isNullOrBlank()
}

/**
 * Las herramientas del ENRIQUECEDOR: explicar una palabra y ponerle un dibujo.
 *
 * **Aquí el function calling sí se gana el sitio, y conviene saber por qué.** En el CUENTISTA no se
 * lo ganaba: pedirle que incluyera unas palabras y comprobarlo era algo que el modelo ya hacía bien
 * solo, y la herramienta acabó siendo un sello de goma (está contado en `CuentistaService`).
 *
 * Buscar un pictograma es distinto por una razón concreta: **el modelo no sabe qué hay en el
 * banco**. Son cientos de entradas, no caben en un prompt, y no puede adivinarlas. Tiene que
 * preguntar.
 *
 * Y lo que de verdad no se puede hacer en código es el reintento. La palabra que un niño falla casi
 * nunca tiene pictograma propio: "estableció" no lo tendrá jamás. Un `Map` devuelve null y se acabó.
 * El modelo, en cambio, razona *"esto va de fundar, de construir"*, llama otra vez con `construir`,
 * y esa sí está. **Ese salto conceptual es la herramienta ganándose el sitio**, y es exactamente lo
 * que se le pide en el prompt.
 *
 * Los nombres y descripciones van en inglés, como en el resto de herramientas: medido en el Redmi,
 * en español el modelo no las llamaba. Es el protocolo con el modelo, no texto para el niño.
 *
 * Ninguna toca el disco ni llama al motor. Ver [ReglasDeHerramientas].
 */
class HerramientasPictograma(
  private val banco: BancoDePictogramas,
  private val recolector: RecolectorDeTarjeta,
) : ToolSet {

  @Tool(
    description =
      "Looks for a picture of a concept in the picture bank. The bank only has a few hundred " +
        "everyday concepts, so rare or abstract words will not be there. If a word is not found, " +
        "try again with a simpler, more concrete related concept."
  )
  fun findPicture(
    @ToolParam(description = "A single common Spanish noun or verb, in singular.") concept: String
  ): Map<String, Any> {
    val archivo = banco.buscar(concept)
    recolector.anotarIntento(concept, archivo != null, archivo)
    return if (archivo != null) {
      mapOf("found" to true, "concept" to concept)
    } else {
      mapOf("found" to false, "concept" to concept, "hint" to "Try a simpler related concept.")
    }
  }

  @Tool(
    description =
      "Saves the explanation of the word for the child. Call this once you have finished looking " +
        "for a picture."
  )
  fun saveExplanation(
    @ToolParam(description = "What the word means, in ONE short sentence in Spanish, for a " +
      "7-year-old child.")
    definition: String,
    @ToolParam(description = "One short everyday sentence in Spanish using the word.")
    example: String,
  ): Map<String, Any> {
    recolector.proponer(definicion = definition, ejemplo = example)
    return mapOf("result" to "saved")
  }
}
