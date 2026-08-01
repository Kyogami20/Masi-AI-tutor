package pe.masi.ui.pantallas

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.masi.R
import pe.masi.servicios.Silabas
import pe.masi.ui.EstadoEscuchar
import pe.masi.ui.MasiViewModel
import pe.masi.ui.componentes.BarraInferior
import pe.masi.ui.componentes.BotonGrande
import pe.masi.ui.componentes.CartaPalabra
import pe.masi.ui.componentes.EstadoCargando
import pe.masi.ui.componentes.FilaDeBotones
import pe.masi.ui.componentes.Hueco
import pe.masi.ui.componentes.PalabraGigante
import pe.masi.ui.componentes.PantallaMasi
import pe.masi.ui.componentes.TextoAdaptado
import pe.masi.ui.theme.AzulCalma
import pe.masi.ui.theme.VerdeLogro

/**
 * Pantalla de Escuchar: el niño lee en voz alta y Masi detecta qué palabras falló.
 *
 * Es el corazón del producto. Nótese lo que NO hay aquí: ni una cruz roja, ni un sonido de fallo,
 * ni un contador de errores. Cuando algo sale mal, sale una pista y se sigue.
 *
 * Y nótese lo que SÍ hay: cuando una palabra falla se puede volver a intentar ahí mismo, y se puede
 * retroceder a un fragmento anterior desde la barra de abajo. Detectar un error y pasar de largo no
 * enseña nada; lo que enseña es volver a intentarlo con la pista delante.
 *
 * La estructura —texto con scroll arriba, acciones en medio, navegación abajo— viene de un fallo
 * real: con fragmentos largos, los botones de acción acababan encima de las flechas.
 */
@Composable
fun EscucharScreen(vm: MasiViewModel, onTerminar: () -> Unit) {
  val estado by vm.escuchar.collectAsStateWithLifecycle()
  val unidades by vm.unidades.collectAsStateWithLifecycle()
  val indice by vm.indice.collectAsStateWithLifecycle()
  val amplitud by vm.amplitud.collectAsStateWithLifecycle()
  val practicando by vm.practicando.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var tienePermiso by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val pedirPermiso =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      tienePermiso = it
    }

  LaunchedEffect(Unit) { if (!tienePermiso) pedirPermiso.launch(Manifest.permission.RECORD_AUDIO) }

  val objetivo = practicando ?: unidades.getOrNull(indice)
  val ocupado = estado is EstadoEscuchar.Grabando || estado is EstadoEscuchar.Pensando

  PantallaMasi(
    encabezado =
      when {
        unidades.isEmpty() -> null
        practicando != null -> "Practicando una palabra"
        else -> "${indice + 1} de ${unidades.size}"
      },
    barraInferior = {
      BarraInferior(
        // Durante la grabación no se navega: se perdería el audio a medias.
        onAnterior = if (ocupado || practicando != null) null else vm::anteriorUnidad,
        onSiguiente = if (ocupado || practicando != null) null else vm::siguienteUnidad,
        hayAnterior = indice > 0,
        haySiguiente = indice < unidades.size - 1,
        etiquetaCentro = if (practicando != null) "Volver a la frase" else "Salir",
        onCentro = if (practicando != null) vm::volverAlFragmento else onTerminar,
      )
    },
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Zona de contenido con scroll propio. Por largo que sea el fragmento, los botones no se
      // mueven de su sitio.
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
          when (val e = estado) {
            is EstadoEscuchar.Esperando -> MostrarObjetivo(objetivo)
            is EstadoEscuchar.Grabando -> {
              MostrarObjetivo(objetivo)
              Hueco(24)
              MicrofonoLatiendo(amplitud)
            }
            is EstadoEscuchar.Pensando -> EstadoCargando("Masi te está escuchando…")
            is EstadoEscuchar.Bien ->
              Text(
                text = "¡Muy bien!",
                style = MaterialTheme.typography.displayLarge,
                color = VerdeLogro,
                textAlign = TextAlign.Center,
              )
            is EstadoEscuchar.PalabraLograda -> {
              PalabraGigante(palabra = e.palabra, silabas = Silabas.separar(e.palabra))
              Hueco(12)
              Text(
                text = "¡Lo lograste!",
                style = MaterialTheme.typography.headlineMedium,
                color = VerdeLogro,
                textAlign = TextAlign.Center,
              )
            }
            is EstadoEscuchar.ConPista -> PalabrasQueCostaron(e)
            is EstadoEscuchar.Repetir -> {
              MostrarObjetivo(objetivo)
              Hueco(16)
              // Cuando la transcripción no es de fiar NO se marca error: se pide repetir. Es
              // preferible dejar pasar un fallo real a inventar uno que no ocurrió.
              Text(
                text = "No te escuché bien. ¿Lo dices otra vez?",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
              )
            }
            is EstadoEscuchar.Terminado -> {
              Text(
                text = "¡Terminaste!",
                style = MaterialTheme.typography.displayLarge,
                color = VerdeLogro,
                textAlign = TextAlign.Center,
              )
              Hueco(12)
              Text(
                text = "Leíste ${unidades.size} partes",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
      }

      Acciones(vm, estado, objetivo, practicando, tienePermiso, onTerminar)
      Hueco(12)
    }
  }
}

/**
 * Las palabras de la oración que salieron distintas.
 *
 * Antes aquí solo cabía una, porque el evaluador descartaba el resto. En una oración de diez
 * palabras se puede fallar más de una, y quedarse con la primera significaba perder las demás sin
 * decírselo a nadie.
 *
 * La primera va en grande con su pista: es la que trae explicación recién hecha por el TUTOR y la
 * que se reintenta al pulsar "Léela tú". Las demás van como cartas, ya guardadas para Practicar.
 *
 * **Nunca se escribe lo que el niño dijo mal.** Solo aparece la forma correcta; ver el error
 * escrito en grande es exactamente lo que no hay que reforzar.
 */
@Composable
private fun PalabrasQueCostaron(estado: EstadoEscuchar.ConPista) {
  val principal = estado.principal
  PalabraGigante(palabra = principal.escritura, silabas = principal.silabas)
  Hueco(16)
  Text(
    text = principal.pista,
    style = MaterialTheme.typography.bodyLarge,
    textAlign = TextAlign.Center,
    color = MaterialTheme.colorScheme.onBackground,
    modifier = Modifier.fillMaxWidth(),
  )

  if (principal.yaEstaba) {
    Hueco(8)
    Text(
      text = "Esta ya la estabas practicando",
      style = MaterialTheme.typography.labelMedium,
      textAlign = TextAlign.Center,
      color = VerdeLogro,
      modifier = Modifier.fillMaxWidth(),
    )
  }

  val resto = estado.palabras.drop(1)
  if (resto.isEmpty()) return

  Hueco(24)
  Text(
    text = if (resto.size == 1) "Esta también la guardé" else "Estas también las guardé",
    style = MaterialTheme.typography.labelMedium,
    textAlign = TextAlign.Center,
    color = MaterialTheme.colorScheme.outline,
    modifier = Modifier.fillMaxWidth(),
  )
  Hueco(10)
  Row(
    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    modifier = Modifier.fillMaxWidth(),
  ) {
    resto.forEach { palabra ->
      CartaPalabra(
        palabra = palabra.escritura,
        silabas = palabra.silabas,
        nota = if (palabra.yaEstaba) "ya la practicabas" else null,
        modifier = Modifier.weight(1f, fill = false).widthIn(max = 170.dp),
      )
    }
  }
}

@Composable
private fun Acciones(
  vm: MasiViewModel,
  estado: EstadoEscuchar,
  objetivo: String?,
  practicando: String?,
  tienePermiso: Boolean,
  onTerminar: () -> Unit,
) {
  when (val e = estado) {
    is EstadoEscuchar.Esperando -> {
      // Masi dice en voz alta lo que hay que hacer, siempre. El niño no lee instrucciones.
      LaunchedEffect(objetivo, practicando) {
        if (objetivo != null) {
          vm.leerEnVozAlta(if (practicando != null) "Ahora esta palabra" else "Te toca leer")
        }
      }
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Escúchalo",
          descripcion = stringResource(R.string.cd_escuchar_de_nuevo),
          color = AzulCalma,
          tamano = tamano,
          onClick = {
            if (practicando != null) vm.deletrear(objetivo!!) else objetivo?.let(vm::leerEnVozAlta)
          },
        )
        BotonGrande(
          icono = Icons.Rounded.Mic,
          etiqueta = "Ahora tú",
          descripcion = stringResource(R.string.cd_grabar),
          habilitado = tienePermiso && objetivo != null,
          tamano = tamano,
          onClick = vm::empezarAGrabar,
        )
      }
    }

    is EstadoEscuchar.Grabando ->
      FilaDeBotones(cuantos = 1) { tamano ->
        BotonGrande(
          icono = Icons.Rounded.Stop,
          etiqueta = "Ya terminé",
          descripcion = stringResource(R.string.cd_detener),
          tamano = tamano,
          onClick = vm::terminarDeGrabar,
        )
      }

    is EstadoEscuchar.Pensando -> Unit

    is EstadoEscuchar.Bien ->
      FilaDeBotones(cuantos = 1) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.ArrowForward,
          etiqueta = "Sigamos",
          descripcion = "Continuar",
          color = VerdeLogro,
          tamano = tamano,
          onClick = vm::siguienteUnidad,
        )
      }

    is EstadoEscuchar.PalabraLograda -> {
      LaunchedEffect(e) { vm.leerEnVozAlta("¡Lo lograste!") }
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.Rounded.Refresh,
          etiqueta = "Otra vez",
          descripcion = "Volver a leer esta palabra",
          color = AzulCalma,
          tamano = tamano,
          onClick = vm::practicarPalabraFallada,
        )
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.ArrowForward,
          etiqueta = "Sigamos",
          descripcion = "Continuar",
          color = VerdeLogro,
          tamano = tamano,
          onClick = vm::siguienteUnidad,
        )
      }
    }

    is EstadoEscuchar.ConPista ->
      FilaDeBotones(cuantos = 3) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Óyela",
          descripcion = stringResource(R.string.cd_escuchar_de_nuevo),
          color = AzulCalma,
          tamano = tamano,
          onClick = { vm.deletrear(e.principal.escritura) },
        )
        // El botón que faltaba: intentarlo otra vez, aquí y ahora, con la pista delante.
        BotonGrande(
          icono = Icons.Rounded.Mic,
          etiqueta = "Léela tú",
          descripcion = "Volver a leer esta palabra",
          habilitado = tienePermiso,
          tamano = tamano,
          onClick = { vm.practicarPalabraFallada() },
        )
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.ArrowForward,
          etiqueta = "Sigamos",
          descripcion = "Continuar",
          color = VerdeLogro,
          tamano = tamano,
          onClick = vm::siguienteUnidad,
        )
      }

    is EstadoEscuchar.Repetir ->
      FilaDeBotones(cuantos = 1) { tamano ->
        BotonGrande(
          icono = Icons.Rounded.Refresh,
          etiqueta = "Otra vez",
          descripcion = stringResource(R.string.cd_grabar),
          tamano = tamano,
          onClick = vm::reintentarUnidad,
        )
      }

    is EstadoEscuchar.Terminado -> {
      LaunchedEffect(Unit) { vm.leerEnVozAlta("¡Terminaste! Muy bien.") }
      FilaDeBotones(cuantos = 1) { tamano ->
        BotonGrande(
          icono = Icons.Rounded.Check,
          etiqueta = "Listo",
          descripcion = "Volver al inicio",
          color = VerdeLogro,
          tamano = tamano,
          onClick = onTerminar,
        )
      }
    }
  }
}

@Composable
private fun MostrarObjetivo(objetivo: String?) {
  if (objetivo == null) return
  val esUnaPalabra = !objetivo.trim().contains(' ')
  if (esUnaPalabra) {
    PalabraGigante(palabra = objetivo, silabas = Silabas.separar(objetivo))
  } else {
    TextoAdaptado(texto = Silabas.separarTexto(objetivo), tamano = 30)
  }
}

/** El micrófono late con la voz. Confirma al niño que se le está oyendo. */
@Composable
private fun MicrofonoLatiendo(amplitud: Int) {
  val escala by
    animateFloatAsState(
      targetValue = 1f + (amplitud / 32767f).coerceIn(0f, 1f) * 0.7f,
      label = "latido",
    )
  Box(
    modifier =
      Modifier.size(96.dp)
        .scale(escala)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer)
  )
}
