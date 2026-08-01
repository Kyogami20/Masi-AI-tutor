package pe.masi.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.masi.MasiApplication
import pe.masi.datos.Tarjeta
import pe.masi.demo.DatosPrecocinados
import pe.masi.descarga.DescargaModelo
import pe.masi.descarga.ImportadorSAF
import pe.masi.descarga.ProgresoDescarga
import pe.masi.media.GrabadorAudio
import pe.masi.media.aPng
import pe.masi.media.recortar
import pe.masi.motor.EstadoModelo
import pe.masi.motor.EstadoMotor
import pe.masi.motor.ModeloLocal
import pe.masi.motor.OrdenContenido
import pe.masi.servicios.ErrorLectura
import pe.masi.servicios.Lectura
import pe.masi.servicios.Repaso
import pe.masi.servicios.ResultadoLector
import pe.masi.servicios.Silabas
import pe.masi.servicios.Veredicto

/** Qué está pasando en la pantalla de Leer. */
sealed interface EstadoLeer {
  data object Encuadrando : EstadoLeer

  /**
   * La foto está tomada y se le enseña al niño para que decida.
   *
   * Este paso existe por dos razones. Para el niño: ver lo que capturó y poder repetirlo antes de
   * esperar medio minuto a que el modelo lo procese. Para quien desarrolla: si la transcripción
   * sale mal, lo primero que hay que saber es si la foto era legible, y sin verla no se sabe.
   */
  data class Confirmando(val fotoPng: ByteArray) : EstadoLeer {
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
  }

  /**
   * El modelo esta trabajando. Lleva la cuenta de palabras ya transcritas.
   *
   * Transcribir una pagina son 40 segundos largos de generacion token a token, mas 10-20 de cargar
   * el encoder de vision. Es latencia inherente al modelo, no un fallo; lo que si se puede evitar
   * es que PAREZCA un fallo, y para eso esta la cifra que sube.
   */
  data class Mirando(val palabras: Int = 0) : EstadoLeer

  data class Leida(val lectura: Lectura) : EstadoLeer

  /** La foto salió movida o no había texto. Se repite sin culpar al niño. */
  data object Repetir : EstadoLeer
}

/** Qué está pasando en la pantalla de Escuchar. */
sealed interface EstadoEscuchar {
  data object Esperando : EstadoEscuchar

  data object Grabando : EstadoEscuchar

  data object Pensando : EstadoEscuchar

  /** Leyó bien. Celebración y a la siguiente. */
  data object Bien : EstadoEscuchar

  data class ConPista(
    val error: ErrorLectura,
    val pista: String,
    val silabas: String,
    /** true si el niño acaba de reintentar esta misma palabra y volvió a salir distinta. */
    val esReintento: Boolean = false,
  ) : EstadoEscuchar

  /** El niño acertó al reintentar la palabra que había fallado. */
  data class PalabraLograda(val palabra: String) : EstadoEscuchar

  /** No se entendió el audio. Se pide repetir; jamás se marca un error dudoso. */
  data object Repetir : EstadoEscuchar

  data object Terminado : EstadoEscuchar
}

/**
 * Qué está pasando con la tarjeta que se está practicando.
 *
 * Practicar es leer en voz alta y que Masi escuche, igual que en la pantalla de Escuchar pero con
 * una palabra suelta. Antes esta pantalla tenía dos botones de autoevaluación —"¡La sé!" y "Otra
 * vez"— y nadie oía al niño: la repetición espaciada avanzaba según lo que el niño (o el adulto)
 * declarara. Ahora **el nivel lo decide el veredicto del modelo**, que es el sentido entero de que
 * la app tenga un micrófono.
 */
sealed interface EstadoTarjeta {
  data object Mostrando : EstadoTarjeta

  data object Grabando : EstadoTarjeta

  data object Pensando : EstadoTarjeta

  /** La leyó bien. La tarjeta sube de peldaño y vuelve dentro de más días. */
  data object Lograda : EstadoTarjeta

  /** Salió distinta. Se enseña la forma correcta en grande y se puede reintentar ahí mismo. */
  data class ConPista(val pista: String) : EstadoTarjeta

  /** No se entendió el audio. Nunca se cuenta como fallo. */
  data object Repetir : EstadoTarjeta
}

/** Estado de la provisión del modelo, antes de que la app pueda hacer nada. */
sealed interface EstadoArranque {
  data object Comprobando : EstadoArranque

  data object FaltaModelo : EstadoArranque

  data class Trayendo(val fraccion: Float, val recibidos: Long, val totales: Long) : EstadoArranque

  data class NoSePudo(val mensaje: String) : EstadoArranque

  data object Encendiendo : EstadoArranque

  data object Listo : EstadoArranque
}

class MasiViewModel(app: Application) : AndroidViewModel(app) {

  private val contenedor
    get() = (getApplication<Application>() as MasiApplication).contenedor

  private val grabador = GrabadorAudio()

  // --- arranque --------------------------------------------------------------------------------

  private val _arranque = MutableStateFlow<EstadoArranque>(EstadoArranque.Comprobando)
  val arranque: StateFlow<EstadoArranque> = _arranque.asStateFlow()

  val estadoMotor: StateFlow<EstadoMotor> = contenedor.motor.estado

  private var trabajoDescarga: Job? = null

  val modoDemo: StateFlow<Boolean> =
    contenedor.preferencias.modoDemo.stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val prefiereOpenDyslexic: StateFlow<Boolean> =
    contenedor.preferencias.prefiereOpenDyslexic.stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      false,
    )

  val ordenContenido: StateFlow<OrdenContenido> =
    contenedor.preferencias.ordenContenido
      .map { runCatching { OrdenContenido.valueOf(it) }.getOrDefault(OrdenContenido.GALLERY) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, OrdenContenido.GALLERY)

  fun comprobarModelo() {
    viewModelScope.launch {
      // En modo demo no hace falta ni el archivo: la demo entra igual.
      if (modoDemo.value) {
        _arranque.value = EstadoArranque.Listo
        return@launch
      }
      when (ModeloLocal.estado(getApplication())) {
        is EstadoModelo.Listo -> encenderMotor()
        else -> _arranque.value = EstadoArranque.FaltaModelo
      }
    }
  }

  fun encenderMotor() {
    viewModelScope.launch {
      _arranque.value = EstadoArranque.Encendiendo
      val ok = contenedor.motor.arrancar()
      _arranque.value =
        if (ok) EstadoArranque.Listo
        else EstadoArranque.NoSePudo("No pude encender el motor en este teléfono")

      // Los encoders de vision y audio se cargan bajo demanda, asi que la PRIMERA foto paga
      // 10-20 s extra. Gastarlos ahora, mientras el nino esta en la pantalla de inicio, los quita
      // del momento en que mas molestan.
      if (ok && !modoDemo.value) {
        viewModelScope.launch { contenedor.motor.precalentarVision() }
      }
    }
  }

  fun descargarModelo() {
    trabajoDescarga?.cancel()
    trabajoDescarga =
      viewModelScope.launch {
        DescargaModelo.descargar(getApplication()).collect { manejarProgreso(it) }
      }
  }

  fun importarModelo(origen: Uri) {
    trabajoDescarga?.cancel()
    trabajoDescarga =
      viewModelScope.launch {
        ImportadorSAF.importar(getApplication(), origen).collect { manejarProgreso(it) }
      }
  }

  fun cancelarDescarga() {
    trabajoDescarga?.cancel()
    trabajoDescarga = null
    _arranque.value = EstadoArranque.FaltaModelo
  }

  private fun manejarProgreso(progreso: ProgresoDescarga) {
    when (progreso) {
      is ProgresoDescarga.EnCurso ->
        _arranque.value =
          EstadoArranque.Trayendo(progreso.fraccion, progreso.recibidos, progreso.totales)
      is ProgresoDescarga.Terminada -> encenderMotor()
      is ProgresoDescarga.Fallida -> _arranque.value = EstadoArranque.NoSePudo(progreso.mensaje)
    }
  }

  // --- Leer ------------------------------------------------------------------------------------

  private val _leer = MutableStateFlow<EstadoLeer>(EstadoLeer.Encuadrando)
  val leer: StateFlow<EstadoLeer> = _leer.asStateFlow()

  fun reiniciarLeer() {
    _leer.value = EstadoLeer.Encuadrando
  }

  /** La cámara disparó. No se procesa nada todavía: primero el niño mira lo que salió. */
  fun fotoTomada(fotoPng: ByteArray?) {
    _leer.value =
      if (fotoPng == null || fotoPng.isEmpty()) EstadoLeer.Repetir
      else EstadoLeer.Confirmando(fotoPng)
  }

  /**
   * Manda al modelo la parte recortada de la foto.
   *
   * Recortar no es cosmetica: el encoder de vision gasta un presupuesto FIJO de tokens sea cual sea
   * el tamano de la imagen, asi que cada centimetro de mesa o de mano se come tokens que deberian
   * estar describiendo letras. Ademas, quitar de la foto el texto que no interesa evita que el
   * modelo lo transcriba, y eso son segundos de generacion menos.
   *
   * @param recorte rectangulo en coordenadas relativas (0..1), o null para usar la foto entera.
   */
  fun confirmarFoto(recorte: Rect? = null) {
    val fotoPng = (_leer.value as? EstadoLeer.Confirmando)?.fotoPng ?: return
    viewModelScope.launch {
      _leer.value = EstadoLeer.Mirando()
      val recortada = withContext(Dispatchers.Default) { aplicarRecorte(fotoPng, recorte) }
      val resultado =
        if (modoDemo.value) DatosPrecocinados.leerPagina()
        else
          contenedor.lector.leerPagina(recortada) { palabras ->
            _leer.value = EstadoLeer.Mirando(palabras)
          }

      _leer.value =
        when (resultado) {
          is ResultadoLector.Exito -> {
            prepararEscucha(resultado.lectura)
            EstadoLeer.Leida(resultado.lectura)
          }
          // Ambos casos se le presentan al niño igual: "vamos a intentarlo otra vez". Distinguir
          // entre "foto borrosa" y "falló el modelo" solo le sirve a quien depura.
          is ResultadoLector.NoSeEntiende -> EstadoLeer.Repetir
          is ResultadoLector.Fallo -> EstadoLeer.Repetir
        }
    }
  }

  private fun aplicarRecorte(fotoPng: ByteArray, recorte: Rect?): ByteArray {
    if (recorte == null) return fotoPng
    return try {
      val bitmap = BitmapFactory.decodeByteArray(fotoPng, 0, fotoPng.size) ?: return fotoPng
      bitmap.recortar(recorte).aPng()
    } catch (e: Exception) {
      // Un recorte fallido nunca debe dejar al nino sin foto: se manda la original.
      fotoPng
    }
  }

  fun leerEnVozAlta(texto: String) {
    contenedor.voz.decir(texto)
  }

  /** Dice la palabra entera y luego la repite despacio. Nunca la deletrea. */
  fun deletrear(palabra: String) {
    contenedor.voz.decirPalabra(palabra)
  }

  // --- Escuchar --------------------------------------------------------------------------------

  private val _escuchar = MutableStateFlow<EstadoEscuchar>(EstadoEscuchar.Esperando)
  val escuchar: StateFlow<EstadoEscuchar> = _escuchar.asStateFlow()

  private val _unidades = MutableStateFlow<List<String>>(emptyList())

  /** Las frases (o palabras) que toca leer, una por una. */
  val unidades: StateFlow<List<String>> = _unidades.asStateFlow()

  private val _indice = MutableStateFlow(0)
  val indice: StateFlow<Int> = _indice.asStateFlow()

  val amplitud: StateFlow<Int> = grabador.amplitud
  val milisGrabados: StateFlow<Long> = grabador.milisTranscurridos

  /** Cuántas tarjetas nuevas van en esta sesión. Tope de 5: un niño frustrado abandona. */
  private var nuevasEnSesion = 0

  val unidadActual: String?
    get() = _unidades.value.getOrNull(_indice.value)

  /**
   * Deja lista la sesión de lectura con los fragmentos de la página.
   *
   * Aquí hubo un `when` sobre el modo de escucha, y su rama "palabra por palabra" sustituía el
   * texto entero por las 8 palabras más difíciles: el niño leía ocho palabras sueltas y la página
   * se quedaba sin leer. Ya no hay modos. La unidad es siempre el fragmento, y los fragmentos
   * cubren el texto completo por construcción (ver [pe.masi.servicios.Segmentador]).
   */
  private fun prepararEscucha(lectura: Lectura) {
    _unidades.value = lectura.fragmentos
    _indice.value = 0
    nuevasEnSesion = 0
    _practicando.value = null
    _escuchar.value = EstadoEscuchar.Esperando
  }

  private var trabajoGrabacion: Job? = null

  /**
   * La palabra que se esta reintentando, o null si se lee el fragmento completo.
   *
   * Cuando el nino falla una palabra, lo pedagogicamente util es intentarla otra vez ahi mismo, no
   * esperar a que reaparezca dentro de tres dias en una tarjeta. Mientras esto no sea null, grabar
   * evalua solo esa palabra y no avanza el indice del fragmento.
   */
  private val _practicando = MutableStateFlow<String?>(null)
  val practicando: StateFlow<String?> = _practicando.asStateFlow()

  fun empezarAGrabar() {
    if (grabador.estaGrabando) return
    val objetivo = _practicando.value ?: unidadActual ?: return
    val esPractica = _practicando.value != null
    _escuchar.value = EstadoEscuchar.Grabando

    // Una palabra suelta cabe en 3 s; un fragmento necesita más. El tope es solo una red: lo
    // normal es que el niño corte antes con "Ya terminé".
    val maxSegundos =
      if (esPractica) GrabadorAudio.MAX_SEG_PALABRA else GrabadorAudio.MAX_SEG_FRASE

    trabajoGrabacion =
      viewModelScope.launch {
        val wav = grabador.grabar(maxSegundos)
        evaluar(objetivo, wav, esPractica)
      }
  }

  /** "Ahora leela tu": vuelve a grabar, pero solo la palabra que fallo. */
  fun practicarPalabraFallada() {
    val error = (_escuchar.value as? EstadoEscuchar.ConPista)?.error ?: return
    contenedor.voz.callar()
    _practicando.value = error.esperado
    _escuchar.value = EstadoEscuchar.Esperando
  }

  /** Deja de practicar la palabra suelta y vuelve al fragmento. */
  fun volverAlFragmento() {
    contenedor.voz.callar()
    _practicando.value = null
    _escuchar.value = EstadoEscuchar.Esperando
  }

  fun terminarDeGrabar() {
    grabador.detener()
  }

  private suspend fun evaluar(objetivo: String, audioWav: ByteArray, esPractica: Boolean) {
    if (audioWav.isEmpty()) {
      _escuchar.value = EstadoEscuchar.Repetir
      return
    }
    _escuchar.value = EstadoEscuchar.Pensando

    when (val veredicto = juzgar(objetivo, audioWav)) {
      is Veredicto.NoSeEntendio -> {
        // Ante la duda, no se marca error. Se pide repetir y no pasa nada.
        _escuchar.value = EstadoEscuchar.Repetir
        contenedor.voz.decir("No te escuché bien. ¿Lo dices otra vez?")
      }

      is Veredicto.Acierto ->
        if (esPractica) {
          // Acerto al reintentar. La tarjeta se queda -volver otro dia es justo lo que ensena- pero
          // sube de nivel: ya no toca tan pronto.
          _escuchar.value = EstadoEscuchar.PalabraLograda(objetivo)
          contenedor.voz.decir("¡Esa es! Muy bien.")
          contenedor.repaso.registrarAciertoDe(objetivo)
        } else {
          _escuchar.value = EstadoEscuchar.Bien
          contenedor.voz.decir("¡Muy bien!")
        }

      is Veredicto.Falla -> {
        _escuchar.value =
          EstadoEscuchar.ConPista(
            veredicto.error,
            veredicto.pista,
            veredicto.silabas,
            esReintento = esPractica,
          )
        contenedor.voz.decir(veredicto.pista)

        if (nuevasEnSesion < Repaso.MAX_NUEVAS_POR_SESION) {
          val creada =
            contenedor.repaso.guardarFallo(
              veredicto.error.esperado,
              veredicto.silabas,
              veredicto.pista,
            )
          if (creada) nuevasEnSesion++
        }
      }
    }
  }

  /** El bucle audio → veredicto, compartido con la pantalla de Practicar. */
  private suspend fun juzgar(objetivo: String, audioWav: ByteArray): Veredicto =
    contenedor.evaluador.evaluar(
      objetivo = objetivo,
      audioWav = audioWav,
      orden = ordenContenido.value,
      demo = modoDemo.value,
    )

  fun siguienteUnidad() {
    contenedor.voz.callar()
    _practicando.value = null
    if (_indice.value + 1 >= _unidades.value.size) {
      _escuchar.value = EstadoEscuchar.Terminado
    } else {
      _indice.value += 1
      _escuchar.value = EstadoEscuchar.Esperando
    }
  }

  /**
   * Vuelve al fragmento anterior.
   *
   * Poder retroceder no es un lujo de navegacion: repetir un trozo que costo es exactamente como se
   * practica la lectura. Sin esto, la unica forma de volver a una frase seria rehacer la foto.
   */
  fun anteriorUnidad() {
    contenedor.voz.callar()
    _practicando.value = null
    if (_indice.value > 0) _indice.value -= 1
    _escuchar.value = EstadoEscuchar.Esperando
  }

  fun reintentarUnidad() {
    contenedor.voz.callar()
    _escuchar.value = EstadoEscuchar.Esperando
  }

  // --- Tarjetas --------------------------------------------------------------------------------

  private val _cola = MutableStateFlow<List<Tarjeta>>(emptyList())
  val cola: StateFlow<List<Tarjeta>> = _cola.asStateFlow()

  private val _tarjetaActual = MutableStateFlow<Tarjeta?>(null)
  val tarjetaActual: StateFlow<Tarjeta?> = _tarjetaActual.asStateFlow()

  val totalTarjetas: StateFlow<Int> =
    contenedor.repaso.todas().map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

  val palabrasDeLaSemana: StateFlow<Int> =
    contenedor.repaso.cuentaCreadasEstaSemana().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

  private val _tocanHoy = MutableStateFlow(0)

  /** Cuantas de la cola tocaban hoy. Solo para sugerir una parada, nunca para cerrar la puerta. */
  val tocanHoy: StateFlow<Int> = _tocanHoy.asStateFlow()

  private val _indiceTarjeta = MutableStateFlow(0)
  val indiceTarjeta: StateFlow<Int> = _indiceTarjeta.asStateFlow()

  private val _estadoTarjeta = MutableStateFlow<EstadoTarjeta>(EstadoTarjeta.Mostrando)
  val estadoTarjeta: StateFlow<EstadoTarjeta> = _estadoTarjeta.asStateFlow()

  private var trabajoTarjeta: Job? = null

  fun cargarRepaso() {
    viewModelScope.launch {
      val todas = contenedor.repaso.colaDePractica()
      _tocanHoy.value = contenedor.repaso.cuantasTocanHoy(todas)
      _cola.value = todas
      _indiceTarjeta.value = 0
      _tarjetaActual.value = todas.firstOrNull()
      _estadoTarjeta.value = EstadoTarjeta.Mostrando
    }
  }

  /** "Léela tú": graba la palabra de la tarjeta. Clip corto, que es una sola palabra. */
  fun grabarTarjeta() {
    if (grabador.estaGrabando) return
    val palabra = _tarjetaActual.value?.palabra ?: return
    contenedor.voz.callar()
    _estadoTarjeta.value = EstadoTarjeta.Grabando

    trabajoTarjeta =
      viewModelScope.launch {
        val wav = grabador.grabar(GrabadorAudio.MAX_SEG_PALABRA)
        evaluarTarjeta(palabra, wav)
      }
  }

  fun terminarDeGrabarTarjeta() {
    grabador.detener()
  }

  /**
   * El veredicto del modelo, y solo él, mueve la tarjeta por la escalera de intervalos.
   *
   * Esto es lo que antes hacían dos botones de autoevaluación. Que un niño de 7 años con dificultad
   * lectora declare si se sabe una palabra no es una señal de aprendizaje: es una señal de cómo se
   * siente. La señal útil es haberla pronunciado bien, y para eso está el micrófono.
   */
  private suspend fun evaluarTarjeta(palabra: String, audioWav: ByteArray) {
    if (audioWav.isEmpty()) {
      _estadoTarjeta.value = EstadoTarjeta.Repetir
      return
    }
    _estadoTarjeta.value = EstadoTarjeta.Pensando

    when (val veredicto = juzgar(palabra, audioWav)) {
      is Veredicto.NoSeEntendio -> {
        // No se toca el nivel. Un audio que no se entiende no dice nada sobre si la sabe o no.
        _estadoTarjeta.value = EstadoTarjeta.Repetir
        contenedor.voz.decir("No te escuché bien. ¿Lo dices otra vez?")
      }

      is Veredicto.Acierto -> {
        _estadoTarjeta.value = EstadoTarjeta.Lograda
        contenedor.voz.decir("¡Esa es! Muy bien.")
        registrarYRecargar(acierto = true)
      }

      is Veredicto.Falla -> {
        // La pista se recalcula con el error de HOY, que no tiene por qué ser el del día que se
        // creó la tarjeta: la guardada puede hablar de una confusión que ya superó.
        _estadoTarjeta.value = EstadoTarjeta.ConPista(veredicto.pista)
        contenedor.voz.decir(veredicto.pista)
      }
    }
  }

  /** "Otra vez": vuelve a intentar la MISMA tarjeta, con la pista delante. */
  fun reintentarTarjeta() {
    contenedor.voz.callar()
    _estadoTarjeta.value = EstadoTarjeta.Mostrando
  }

  /**
   * "Todavía no": se da por no lograda y vuelve al primer peldaño.
   *
   * Es la salida honesta cuando la palabra no sale. Nunca se presenta como un fallo — el botón dice
   * "todavía no", no "no la sé" — pero sí cuenta para la repetición espaciada, porque volverla a
   * ver pronto es exactamente lo que hace falta.
   */
  fun rendirseConTarjeta() {
    contenedor.voz.callar()
    viewModelScope.launch {
      registrarYRecargar(acierto = false)
      avanzarTarjeta()
    }
  }

  private suspend fun registrarYRecargar(acierto: Boolean) {
    val actual = _tarjetaActual.value ?: return
    contenedor.repaso.registrarRepaso(actual, acierto)
    // Se relee de la base para que el nivel y la fecha nuevos se reflejen en la cola.
    val todas = contenedor.repaso.colaDePractica()
    _tocanHoy.value = contenedor.repaso.cuantasTocanHoy(todas)
    _cola.value = todas
  }

  fun siguienteTarjeta() = avanzarTarjeta()

  fun anteriorTarjeta() {
    val cola = _cola.value
    if (cola.isEmpty()) return
    cancelarGrabacionTarjeta()
    _indiceTarjeta.value = (_indiceTarjeta.value - 1 + cola.size) % cola.size
    _tarjetaActual.value = cola[_indiceTarjeta.value]
  }

  /**
   * Pasa a la siguiente tarjeta, **en circulo**.
   *
   * La cola no se vacia nunca. Antes se iba consumiendo hasta dejar la pantalla en "ya practicaste
   * todo por hoy", que para un nino con ganas de seguir es una puerta cerrada sin motivo. La
   * repeticion espaciada decide el ORDEN -lo que toca hoy va delante-, no el permiso.
   */
  private fun avanzarTarjeta() {
    cancelarGrabacionTarjeta()
    val cola = _cola.value
    if (cola.isEmpty()) {
      _tarjetaActual.value = null
      return
    }
    _indiceTarjeta.value = (_indiceTarjeta.value + 1) % cola.size
    _tarjetaActual.value = cola[_indiceTarjeta.value]
  }

  /** Cambiar de tarjeta con el micrófono abierto dejaría un audio evaluándose contra otra palabra. */
  private fun cancelarGrabacionTarjeta() {
    trabajoTarjeta?.cancel()
    trabajoTarjeta = null
    grabador.detener()
    contenedor.voz.callar()
    _estadoTarjeta.value = EstadoTarjeta.Mostrando
  }

  /** Quitar una palabra es siempre una decision explicita, nunca algo que ocurra solo. */
  fun olvidarTarjeta() {
    val actual = _tarjetaActual.value ?: return
    cancelarGrabacionTarjeta()
    viewModelScope.launch {
      contenedor.repaso.olvidar(actual.palabra)
      cargarRepaso()
    }
  }

  // --- Ajustes ---------------------------------------------------------------------------------

  fun ponerModoDemo(valor: Boolean) = viewModelScope.launch {
    contenedor.preferencias.ponerModoDemo(valor)
  }

  fun ponerOpenDyslexic(valor: Boolean) = viewModelScope.launch {
    contenedor.preferencias.ponerOpenDyslexic(valor)
  }

  fun ponerOrdenContenido(valor: OrdenContenido) = viewModelScope.launch {
    contenedor.preferencias.ponerOrdenContenido(valor.name)
  }

  fun callar() = contenedor.voz.callar()

  override fun onCleared() {
    grabador.detener()
    contenedor.voz.callar()
    super.onCleared()
  }
}
