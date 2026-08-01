package pe.masi.motor

/**
 * Los system prompts de los tres agentes, y el prompt de turno del ESCUCHA.
 *
 * Regla que atraviesa todo el archivo: los modelos pequeños pierden los datos enterrados a mitad
 * del prompt. El dato crítico va en la PRIMERA línea, solo, en una frase declarativa plana.
 */
object Prompts {

  /**
   * Foto de una página → el texto que hay en ella. Temperatura 0.2: transcribir no es creativo.
   *
   * Solo se pide el texto, y eso es una decisión de ingeniería con consecuencias medibles. La
   * versión anterior pedía además las sílabas y las palabras difíciles, es decir **el triple de
   * tokens de salida** para una página entera: se comía el presupuesto de contexto, el JSON llegaba
   * cortado y el parser devolvía null. Separar en sílabas y detectar palabras difíciles son reglas
   * fijas del español, así que se hacen en código (ver [pe.masi.servicios.Silabas]), que es más
   * rápido, más fiable y gratis.
   *
   * La única excepción es la marca `|` entre oraciones, y sale a cuenta por aritmética: un `|` es un
   * token, así que segmentar una página entera cuesta unos 30 tokens (~3 s). Una segunda pasada
   * pidiéndole al modelo el texto ya troceado costaría los mismos 400-500 tokens que la primera,
   * o sea otros 45 s largos, y encima con el riesgo de que reescriba lo que ya había transcrito
   * bien. Aquí el modelo segmenta donde más información tiene —viendo la maquetación de la página—
   * y por casi nada.
   *
   * Es una **pista, no un contrato**: [pe.masi.servicios.Segmentador] normaliza lo que llegue y
   * funciona igual desde la puntuación si el modelo la ignora.
   *
   * Se usa `|` y no un salto de línea a propósito: un `\n` literal dentro de una cadena JSON la
   * invalida, y un modelo pequeño lo emite sin escapar con toda naturalidad.
   */
  const val LECTOR =
    """Eres LECTOR. Recibes la foto de una página de un libro escolar peruano.

Devuelve SOLO un JSON con esta forma exacta:
{"texto": "..."}

Reglas:
1. Transcribe EXACTAMENTE el texto que ves en la imagen. No corrijas, no resumas,
   no completes, no traduzcas, no expliques.
2. Pon el carácter | al final de cada oración. Nada más lo separa.
3. Si una palabra está borrosa o no se lee, escribe [?].
4. Si la imagen no tiene texto legible, devuelve {"texto": ""}.
5. Nada de texto fuera del JSON.

Ejemplo de respuesta:
{"texto": "El perro corre por el campo.|La gata duerme en la cama."}"""

  /**
   * Audio del niño → transcripción LITERAL, con los errores incluidos.
   *
   * Este es el agente crítico del proyecto. Un ASR normal está entrenado para producir la
   * transcripción más probable, así que "arregla" el error que necesitamos detectar: oye "bero" en
   * un contexto donde "perro" es abrumadoramente más probable, y escribe "perro". Todo este prompt
   * existe para pelear contra eso. Temperatura 0.1: aquí la creatividad es literalmente el enemigo.
   */
  const val ESCUCHA =
    """Eres un transcriptor fonético. Tu única tarea es escribir los sonidos que oyes.

CRÍTICO: si la persona pronunció mal una palabra, escribe la palabra MAL PRONUNCIADA tal como
sonó, NO la palabra correcta. Si dijo "bero", escribe "bero". Si dijo "pato" donde decía "plato",
escribe "pato". No corrijas. No completes. No adivines. No uses el contexto para arreglar nada.

Es una prueba de lectura: los errores son justamente el dato que hace falta. Corregirlos destruye
el resultado.

Devuelve SOLO un JSON con esta forma exacta:
{"dicho": "..."}

Nada de texto fuera del JSON."""

  /**
   * Error detectado → pista amable. Temperatura 0.7: aquí sí queremos variedad.
   *
   * La versión anterior decía "usa objetos de la vida diaria peruana: la chacra, el mercado, la
   * pelota, el perro, el pan" y el modelo se lo tomó al pie de la letra: soltaba "Mira el pan" o
   * "Lee la pelota" sin relación ninguna con la palabra. Un modelo de 2B efectivos no distingue
   * entre "usa este registro" y "nombra estas cosas". Ahora la regla dice para qué sirve la
   * comparación, y la estructura de la respuesta va marcada paso a paso.
   *
   * También hubo mezcla de idiomas ("¡Buen try!"): es lo normal en un modelo pequeño y multilingüe,
   * y por eso la regla 1 es la del idioma y [pe.masi.servicios.TutorService] filtra lo que salga
   * con inglés dentro.
   */
  const val TUTOR =
    """Hablas con un niño peruano de 7 años que está aprendiendo a leer.

Reglas:
1. Escribe SOLO en español. Ni una palabra en inglés. Nunca.
2. Exactamente 2 frases cortas, ni una más.
3. Primera frase: anima al niño por haberlo intentado.
4. Segunda frase: una pista sobre CÓMO SUENA LA PALABRA que te doy. Nombra la palabra o
   sus sílabas. Habla de sus sonidos, de por dónde empieza, o de la forma de la letra.
5. La pista tiene que ser sobre ESA palabra concreta. No hables de otra cosa.
6. Si comparas con algo, que sea algo que un niño peruano ve todos los días.
7. Nunca digas "mal", "error", "incorrecto", "fallaste".
8. Responde en texto plano, sin JSON, sin viñetas, sin comillas.

Ejemplo. Palabra "perro", el niño leyó "bero":
¡Muy bien por intentarlo! Dice pe-rro: empieza con la p, la de pan."""

  /**
   * Prompt de turno del ESCUCHA. El texto esperado va en la primera línea, solo.
   *
   * @param textoEsperado lo que estaba escrito y el niño debía leer.
   */
  fun turnoEscucha(textoEsperado: String): String =
    """El niño debía leer exactamente esto: "${textoEsperado.trim()}"

Escucha el audio y transcribe LITERALMENTE lo que el niño pronunció, con sus errores.

Devuelve solo: {"dicho": "..."}"""

  /**
   * Prompt de turno del TUTOR.
   *
   * Se le pasa ya masticado: el TUTOR no compara nada, solo explica. La comparación se hizo en
   * código determinista (ver `pe.masi.servicios.DetectorErrores`).
   */
  fun turnoTutor(esperado: String, dicho: String, silabas: String): String =
    """La palabra es "$esperado" y se separa así: $silabas.
El niño leyó "$dicho".

Anímalo y dale una pista para recordar cómo suena "$esperado"."""

  /** Reintento cuando el modelo devolvió algo que no era JSON. */
  fun reintentoJson(formaEsperada: String): String =
    """Tu respuesta anterior no era JSON válido.

Responde de nuevo con SOLO el JSON, sin explicación y sin bloques de código:
$formaEsperada"""
}

/**
 * En qué orden se meten imagen, audio y texto en el mensaje.
 *
 * Está en disputa y merece un A/B: el model card de Gemma 4 dice imagen antes del texto y audio
 * después del texto; el código de producción de Google AI Edge Gallery pone imágenes, luego audio,
 * y el texto al final ("add the text after image and audio for the accurate last token").
 *
 * [GALLERY] es lo que se validó en el Día 0. Cambiar este valor es todo el experimento.
 */
enum class OrdenContenido {
  /** Imagen → audio → texto. El de la Gallery. Por defecto. */
  GALLERY,

  /** Imagen → texto → audio. El del model card oficial. */
  MODEL_CARD,
}
