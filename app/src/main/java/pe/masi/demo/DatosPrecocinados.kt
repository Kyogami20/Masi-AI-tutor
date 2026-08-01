package pe.masi.demo

import kotlinx.coroutines.delay
import pe.masi.datos.Cuento
import pe.masi.servicios.ErrorLectura
import pe.masi.servicios.Lectura
import pe.masi.servicios.FiltroDeCuento
import pe.masi.servicios.PasoCuento
import pe.masi.servicios.PoliticaConservadora
import pe.masi.servicios.ResultadoCuento
import pe.masi.servicios.ResultadoEscucha
import pe.masi.servicios.ResultadoLector
import pe.masi.servicios.RevisionCuento
import pe.masi.servicios.TipoError
import pe.masi.servicios.VersionTexto

/**
 * Modo demo: respuestas fijas, sin tocar el modelo.
 *
 * Esto no es una trampa, es un seguro. El día del evento el teléfono puede estar caliente, la
 * batería al 15 % y el motor tardando el triple de lo normal. Con este interruptor el recorrido
 * completo —foto, lectura, detección, pista, tarjeta— sigue funcionando delante del jurado.
 *
 * Se activa desde la pantalla de ajustes, que está detrás de una pulsación larga. Y si se usa
 * durante una demo, se dice en voz alta. La honestidad técnica genera confianza; que te pillen, no.
 */
object DatosPrecocinados {

  /** Latencia simulada, del orden de la real, para que la animación de carga se vea igual. */
  private const val ESPERA_VISION_MS = 3_500L
  private const val ESPERA_AUDIO_MS = 2_500L
  private const val ESPERA_TUTOR_MS = 1_500L

  /** Lleva las marcas `|` a propósito: es exactamente lo que devuelve el LECTOR de verdad. */
  private const val TEXTO_PAGINA =
    "El perro corre por el campo.|La niña juega con la pelota.|" +
      "Mi mamá compra pan en el mercado."

  suspend fun leerPagina(): ResultadoLector {
    delay(ESPERA_VISION_MS)
    // Pasa por el mismo constructor que la ruta real: si el troceado se rompe, la demo también,
    // y es mejor enterarse ensayando que delante del jurado.
    return ResultadoLector.Exito(Lectura.de(TEXTO_PAGINA))
  }

  /**
   * Simula que el niño leyó "bero" donde decía "perro" — el caso de la demo.
   *
   * Si la frase esperada no contiene "perro", se devuelve una lectura perfecta: es preferible que
   * el modo demo se quede corto a que invente un error donde el niño leyó bien.
   */
  suspend fun escuchar(textoEsperado: String): ResultadoEscucha {
    delay(ESPERA_AUDIO_MS)
    val transcripcion =
      if (textoEsperado.contains("perro", ignoreCase = true)) {
        textoEsperado.replace(Regex("(?i)perro"), "bero")
      } else {
        textoEsperado
      }
    return ResultadoEscucha(
      transcripcion = transcripcion,
      lectura = PoliticaConservadora.evaluar(textoEsperado, transcripcion),
    )
  }

  /**
   * Un cuento fijo, para que el recorrido completo funcione sin cargar el modelo.
   *
   * Se emite por trozos con la misma cadencia aproximada que el modelo real, porque lo que se está
   * enseñando en la demo es precisamente que el cuento aparece mientras se escribe.
   */
  suspend fun escribirCuento(
    alAvanzar: (PasoCuento) -> Unit = {},
    alEscribir: (String) -> Unit = {},
  ): ResultadoCuento {
    alAvanzar(PasoCuento.ELIGIENDO_PALABRAS)
    delay(2_000)
    alAvanzar(PasoCuento.ESCRIBIENDO)

    // Palabra a palabra, con la cadencia aproximada del modelo real: lo que se enseña en la demo es
    // precisamente que el cuento aparece mientras se escribe.
    val acumulado = StringBuilder()
    for (palabra in RESPUESTA_DEMO.split(" ")) {
      acumulado.append(palabra).append(' ')
      alEscribir(acumulado.toString().trim())
      delay(90)
    }

    alAvanzar(PasoCuento.GUARDANDO)
    delay(1_500)

    // **Pasa por el mismo camino que la ruta real**, igual que `leerPagina`. La versión anterior
    // construía el `Cuento` a mano con su título ya puesto, así que la demo seguía funcionando
    // aunque `FiltroDeCuento.partir` estuviera roto — y eso se descubriría delante del jurado, que
    // es exactamente cuando no se quiere descubrir. Ahora, si el troceado del título falla, falla
    // también aquí y se ve ensayando.
    val crudo = FiltroDeCuento.partir(RESPUESTA_DEMO)
    val revision =
      FiltroDeCuento.revisar(crudo.titulo, crudo.texto, PALABRAS_DEMO) as RevisionCuento.Aceptado

    return ResultadoCuento.Exito(
      Cuento(
        id = -1,
        titulo = revision.titulo,
        texto = revision.texto,
        palabrasUsadas = PALABRAS_DEMO.joinToString(", "),
      ),
      Lectura.de(revision.texto, VersionTexto.CURADA),
    )
  }

  private val SALTO = System.lineSeparator()

  private val PALABRAS_DEMO = listOf("perro", "mercado")

  /**
   * Exactamente con la forma que devuelve el CUENTISTA de verdad: título en la primera línea, una
   * línea en blanco, y después el cuento.
   *
   * El título habla del mercado porque el cuento ocurre en el mercado. Parece obvio, pero la versión
   * anterior se titulaba "El perro de la chacra" y en el cuento no salía ninguna chacra: un descuido
   * al escribirlo a mano que solo se nota leyéndolo entero, que es justo lo que hace un jurado.
   */
  private val RESPUESTA_DEMO =
    "El perro perdido en el mercado" + SALTO + SALTO +
      "Rosa vivía cerca del mercado, en un pueblo de la sierra. Cada mañana su perro la " +
      "acompañaba a comprar pan. Un día el perro se perdió entre los puestos de fruta. " +
      "Rosa lo buscó por todas partes y no lo encontraba. Entonces oyó un ladrido detrás " +
      "de unas cajas. ¡Ahí estaba! El perro movía la cola muy contento. Rosa lo abrazó " +
      "fuerte y volvieron juntos a casa."

  suspend fun explicar(error: ErrorLectura): String {
    delay(ESPERA_TUTOR_MS)
    return when (error.tipo) {
      TipoError.SUSTITUCION_INICIAL ->
        "¡Casi! Dice pe-rro. Mira la P: tiene su barriguita mirando adelante."
      TipoError.INVERSION -> "¡Muy bien! Están todas las letras, solo cambiaron de sitio."
      else -> "¡Buen intento! Vamos a decirla otra vez, despacito."
    }
  }
}
