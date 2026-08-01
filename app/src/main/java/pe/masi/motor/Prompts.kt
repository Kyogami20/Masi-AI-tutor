package pe.masi.motor

/**
 * Los system prompts de los tres agentes, y el prompt de turno del ESCUCHA.
 *
 * Regla que atraviesa todo el archivo: los modelos pequeños pierden los datos enterrados a mitad
 * del prompt. El dato crítico va en la PRIMERA línea, solo, en una frase declarativa plana.
 */
object Prompts {

  private const val SALTO = "\n"

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
    """El niño debía leer esto: "${textoEsperado.trim()}"

Escucha el audio y escribe LITERALMENTE lo que pronunció, con sus errores.

No copies la frase de arriba. No escribas nada después del JSON.

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

  /**
   * El CUENTISTA: un cuento corto con las palabras que al niño le cuestan.
   *
   * **Nótese lo que NO hay: ni una sola lista de ejemplos.** La primera versión decía "ambientado en
   * el Perú: la sierra, la costa, la selva, el mercado, la chacra, el colegio" y daba nombres a
   * elegir, y el modelo las enumeró todas, una por frase, en orden:
   *
   * > Rosa fue a la sierra. Túpac fue al mercado. Killa vio el río. Manuel fue a la farm.
   *
   * Es el mismo error que ya se había cometido en [TUTOR] —está documentado ahí arriba— y se repitió
   * aquí: **un modelo pequeño no distingue entre "usa este registro" y "nombra estas cosas"**. La
   * lista de nombres propios es la peor de todas, porque le da un reparto y se siente obligado a
   * sacarlos a todos.
   *
   * Ahora se describe la FORMA de un cuento —un protagonista, una cosa que le pasa— en vez de sus
   * ingredientes. Y en español: aquel `farm` del ejemplo venía literalmente del prompt en inglés.
   */
  const val CUENTISTA =
    """Escribes cuentos cortos para un niño peruano de 7 años que está aprendiendo a leer.

Reglas del cuento:
1. UN solo protagonista, un niño o una niña, con nombre peruano. Nadie más importa.
2. Le pasa UNA sola cosa: un problema pequeño que resuelve al final.
3. Ocurre en un lugar del Perú, y ese lugar se nota en lo que el niño ve y hace.
4. Entre 80 y 120 palabras.
5. Frases cortas. Palabras sencillas, salvo las que te pida practicar.
6. Termina bien. Nada de miedo, peleas, ni tristeza.
7. Escribe en español, y nada más que el título y el cuento. Sin explicaciones ni comentarios.

Formato exacto de tu respuesta:
- La PRIMERA línea es el título. De tres a cinco palabras. Sin punto final.
- La segunda línea, en blanco.
- A partir de la tercera, el cuento.

Ejemplo:
El nido de Rosa

Rosa vivía cerca de la chacra de su abuelo. Cada mañana..."""

  /**
   * Arranques narrativos. Se elige UNO al azar, nunca se dan como lista.
   *
   * Esa distinción es la lección más cara de este archivo: una lista de ejemplos en el prompt hace
   * que un modelo pequeño la enumere entera, una por frase. Pasó en [TUTOR] y volvió a pasar en el
   * CUENTISTA — "Rosa fue a la sierra. Túpac fue al mercado. Killa vio el río." Dando uno solo, el
   * modelo no tiene nada que enumerar.
   *
   * Y son el seguro de que dos cuentos seguidos salgan distintos. La semilla aleatoria del sampler
   * debería bastar, pero medido en el Redmi **no bastaba**: con las mismas tres palabras el modelo
   * devolvía el mismo cuento palabra por palabra. La sospecha es la decodificación especulativa
   * (`MTP=true`), que al verificar contra el modelo borrador puede acabar siendo voraz. Cambiar la
   * entrada, en cambio, cambia la salida pase lo que pase con el muestreo.
   */
  private val ARRANQUES =
    listOf(
      "Empieza una mañana temprano.",
      "Empieza cuando ya está oscureciendo.",
      "Empieza en medio de una lluvia fuerte.",
      "Empieza un día de mucho sol.",
      "Empieza cuando el protagonista se despierta tarde.",
      "Empieza con el protagonista buscando algo que perdió.",
      "Empieza con el protagonista ayudando a alguien de su familia.",
      "Empieza con el protagonista yendo a un sitio al que no había ido nunca.",
      "Empieza con el protagonista encontrando un animal.",
      "Empieza con el protagonista llegando tarde a algún sitio.",
      "Empieza con el protagonista cargando algo pesado.",
      "Empieza con el protagonista escuchando un ruido raro.",
    )

  /**
   * El encargo, con las palabras dentro.
   *
   * Van numeradas y **en orden aleatorio**: en un prompt de turno corto lo último pesa, así que
   * rotarlas reparte el peso y de paso cambia la entrada entre una llamada y la siguiente.
   */
  fun turnoCuentista(palabras: List<String>): String {
    val lista =
      palabras.shuffled().mapIndexed { i, p -> "${i + 1}. $p" }.joinToString(separator = SALTO)
    return """Escribe un cuento donde aparezcan estas palabras, repartidas de forma natural:

$lista

${ARRANQUES.random()}

Recuerda: un protagonista, una cosa que le pasa, y termina bien."""
  }

  /**
   * Segundo intento cuando el cuento se dejó palabras.
   *
   * Se le devuelve el cuento entero, no solo las que faltan: pedirle "añade estas dos palabras" sin
   * contexto le hace escribir dos frases sueltas y pegarlas al final.
   */
  fun turnoCuentistaReintento(cuento: String, faltan: List<String>): String =
    """Este cuento está bien, pero le faltan palabras:

$cuento

Reescríbelo entero, parecido pero incluyendo también: ${faltan.joinToString(", ")}.

Escribe SOLO el cuento nuevo."""

  /**
   * El ENRIQUECEDOR: explica una palabra y le busca un dibujo. **El rol con herramientas.**
   *
   * En inglés, como el resto del protocolo con el modelo, por lo mismo que ya está medido: en
   * español no llamaba a las herramientas. La definición y el ejemplo salen en español porque se le
   * piden dentro.
   *
   * La instrucción que importa es la del reintento. Sin ella el modelo llama una vez, no encuentra
   * nada y se rinde — y entonces la herramienta no aporta más que un `Map` en Kotlin. **Lo que se
   * le pide aquí es exactamente lo que el código no puede hacer**: darse cuenta de que "estableció"
   * trata de construir y volver a buscar con esa idea.
   */
  const val ENRIQUECEDOR =
    """You help a 7-year-old Peruvian child understand a Spanish word they struggle to read.

For EVERY word you MUST do these steps in order:

1. Call `find_picture` with the word itself.

2. If found is false, think about what the word MEANS and call `find_picture` again with a
   simpler, more concrete everyday concept that represents it.
   Examples of this reasoning:
   - "estableció" is about building something new, so try "construir".
   - "veloz" is about running fast, so try "correr".
   - "chacra" is a piece of farmland, so try "campo".
   You may try up to 3 times in total. Then stop looking.

3. Call `save_explanation` with the definition and an example sentence, both in SPANISH:
   - definition: ONE short sentence a 7-year-old understands. Do not repeat the word itself.
   - example: ONE short everyday sentence that uses the word.

Do NOT write anything outside of tool calls."""

  /** El encargo del ENRIQUECEDOR cuando no hay dibujo todavía: toca buscarlo. */
  fun turnoEnriquecedor(palabra: String): String = """The word is "$palabra". Start with step 1."""

  /**
   * El encargo cuando el dibujo **ya lo encontró el código**.
   *
   * La búsqueda exacta la resuelve un `Map` en microsegundos, así que hacérsela al modelo era gastar
   * una ida y vuelta entera para llegar al mismo sitio. Aquí se le dice que ya está y salta directo
   * a explicar. La herramienta de guardar sigue en juego: da salida estructurada sin parsear texto.
   */
  fun turnoEnriquecedorConDibujo(palabra: String): String =
    """The word is "$palabra". The picture is already found, so SKIP steps 1 and 2.
Go straight to step 3 and call `save_explanation`."""

  /**
   * Pide un título para un cuento ya escrito.
   *
   * Es una llamada aparte y muy corta —unos diez tokens de salida, un par de segundos— en vez de
   * pedirle el título junto al cuento. Pedirlo todo de una vez obligaría a partir la respuesta por
   * la primera línea, y ya sabemos cómo acaba eso: el modelo se salta el formato, el parser hace lo
   * que puede y el fallo aparece en la biblioteca del niño.
   *
   * Los títulos ya usados van dentro para que no repita, pero la comprobación de verdad la hace SQL
   * al volver: pedirle a un modelo que recuerde una lista no es una garantía.
   */
  fun turnoTitulo(cuento: String, yaUsados: List<String>): String {
    val evitar =
      if (yaUsados.isEmpty()) ""
      else SALTO + SALTO + "No uses ninguno de estos: " + yaUsados.joinToString("; ") + "."
    return """Lee este cuento:

$cuento

Escribe un título para él.

Reglas:
1. Como mucho CINCO palabras. Mejor tres.
2. Que diga de qué va el cuento. No copies su primera frase.
3. Solo el título, sin comillas, sin punto final, sin explicar nada.$evitar"""
  }

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
