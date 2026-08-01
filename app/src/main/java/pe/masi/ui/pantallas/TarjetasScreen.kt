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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import pe.masi.ui.EstadoTarjeta
import pe.masi.ui.MasiViewModel
import pe.masi.ui.componentes.BarraInferior
import pe.masi.ui.componentes.BotonGrande
import pe.masi.ui.componentes.EstadoCargando
import pe.masi.ui.componentes.FilaDeBotones
import pe.masi.ui.componentes.Hueco
import pe.masi.ui.componentes.PalabraGigante
import pe.masi.ui.componentes.PantallaMasi
import pe.masi.ui.theme.AzulCalma
import pe.masi.ui.theme.VerdeLogro

/**
 * Repaso espaciado: las palabras que el niño falló, devueltas cada vez más tarde.
 *
 * **Aquí se lee en voz alta y Masi escucha.** Esta pantalla tuvo dos botones de autoevaluación
 * —"¡La sé!" y "Otra vez"— con los que el niño declaraba si se sabía la palabra y la escalera de
 * intervalos se movía según esa declaración. Que un niño de 7 años con dificultad lectora diga si
 * se sabe una palabra informa de cómo se siente, no de si la lee bien; y encima convertía el
 * micrófono, que es la razón de ser de la app, en algo que aquí no existía. Ahora el peldaño lo
 * decide el veredicto del modelo.
 *
 * Dos reglas de producto que se conservan intactas:
 *  - **Las tarjetas nunca desaparecen.** La repetición espaciada decide el ORDEN, no el permiso
 *    para practicar. Una palabra solo se va si alguien pulsa "Quitar" y lo confirma.
 *  - **Nunca se muestra en grande la forma equivocada.** Lo que se ve siempre es la correcta.
 */
@Composable
fun TarjetasScreen(vm: MasiViewModel, onVolver: () -> Unit, onLeer: () -> Unit) {
  val tarjeta by vm.tarjetaActual.collectAsStateWithLifecycle()
  val cola by vm.cola.collectAsStateWithLifecycle()
  val indice by vm.indiceTarjeta.collectAsStateWithLifecycle()
  val tocanHoy by vm.tocanHoy.collectAsStateWithLifecycle()
  val estado by vm.estadoTarjeta.collectAsStateWithLifecycle()
  val amplitud by vm.amplitud.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var confirmarBorrado by remember { mutableStateOf(false) }

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

  LaunchedEffect(Unit) {
    vm.cargarRepaso()
    if (!tienePermiso) pedirPermiso.launch(Manifest.permission.RECORD_AUDIO)
  }

  val actual = tarjeta
  if (actual == null) {
    SinPalabras(vm, onLeer, onVolver)
    return
  }

  val ocupado = estado is EstadoTarjeta.Grabando || estado is EstadoTarjeta.Pensando

  PantallaMasi(
    encabezado =
      if (tocanHoy > 0) "Palabra ${indice + 1} de ${cola.size} · hoy tocan $tocanHoy"
      else "Palabra ${indice + 1} de ${cola.size}",
    accionEncabezado = {
      // Quitar es la única acción de toda la app que destruye algo, así que va discreta y con
      // confirmación.
      TextButton(onClick = { confirmarBorrado = true }, enabled = !ocupado) {
        Text(
          "Quitar",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.outline,
        )
      }
    },
    barraInferior = {
      BarraInferior(
        // Durante la grabación no se navega: se perdería el audio a medias.
        onAnterior = if (ocupado) null else vm::anteriorTarjeta,
        onSiguiente = if (ocupado) null else vm::siguienteTarjeta,
        etiquetaCentro = "Salir",
        onCentro = onVolver,
      )
    },
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
          when (val e = estado) {
            is EstadoTarjeta.Pensando -> EstadoCargando("Masi te está escuchando…")

            else -> {
              PalabraGigante(palabra = actual.palabra, silabas = actual.silabas)
              Hueco(16)
              when (e) {
                is EstadoTarjeta.Lograda ->
                  Text(
                    text = "¡Lo lograste!",
                    style = MaterialTheme.typography.headlineMedium,
                    color = VerdeLogro,
                    textAlign = TextAlign.Center,
                  )

                // La pista es la de HOY, recalculada con el error de este intento: la guardada
                // puede hablar de una confusión que el niño ya superó.
                is EstadoTarjeta.ConPista ->
                  Text(
                    text = e.pista,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                  )

                is EstadoTarjeta.Repetir ->
                  Text(
                    text = "No te escuché bien. ¿Lo dices otra vez?",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                  )

                is EstadoTarjeta.Grabando -> MicrofonoLatiendo(amplitud)

                else ->
                  Text(
                    text = actual.pista,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                  )
              }
            }
          }
        }
      }

      AccionesTarjeta(vm, estado, actual.palabra, tienePermiso)
      Hueco(12)
    }
  }

  if (confirmarBorrado) {
    AlertDialog(
      onDismissRequest = { confirmarBorrado = false },
      title = { Text("¿Quitar \"${actual.palabra}\"?") },
      text = {
        Text(
          "Dejará de aparecer para practicar. Solo quítala si ya la domina.",
          style = MaterialTheme.typography.labelMedium,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmarBorrado = false
            vm.olvidarTarjeta()
          }
        ) {
          Text("Quitar")
        }
      },
      dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") } },
    )
  }
}

@Composable
private fun AccionesTarjeta(
  vm: MasiViewModel,
  estado: EstadoTarjeta,
  palabra: String,
  tienePermiso: Boolean,
) {
  when (estado) {
    is EstadoTarjeta.Mostrando -> {
      LaunchedEffect(palabra) { vm.leerEnVozAlta("Lee esta palabra") }
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Óyela",
          descripcion = stringResource(R.string.cd_escuchar_de_nuevo),
          color = AzulCalma,
          tamano = tamano,
          onClick = { vm.deletrear(palabra) },
        )
        BotonGrande(
          icono = Icons.Rounded.Mic,
          etiqueta = "Léela tú",
          descripcion = stringResource(R.string.cd_grabar),
          habilitado = tienePermiso,
          tamano = tamano,
          onClick = vm::grabarTarjeta,
        )
      }
    }

    is EstadoTarjeta.Grabando ->
      FilaDeBotones(cuantos = 1) { tamano ->
        BotonGrande(
          icono = Icons.Rounded.Stop,
          etiqueta = "Ya terminé",
          descripcion = stringResource(R.string.cd_detener),
          tamano = tamano,
          onClick = vm::terminarDeGrabarTarjeta,
        )
      }

    is EstadoTarjeta.Pensando -> Unit

    is EstadoTarjeta.Lograda ->
      FilaDeBotones(cuantos = 1) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.ArrowForward,
          etiqueta = "Sigamos",
          descripcion = "Continuar",
          color = VerdeLogro,
          tamano = tamano,
          onClick = vm::siguienteTarjeta,
        )
      }

    is EstadoTarjeta.ConPista ->
      FilaDeBotones(cuantos = 3) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Óyela",
          descripcion = stringResource(R.string.cd_escuchar_de_nuevo),
          color = AzulCalma,
          tamano = tamano,
          onClick = { vm.deletrear(palabra) },
        )
        // Reintentar la misma palabra con la pista delante es lo que enseña. Sin este botón, la
        // pista se decía y el niño pasaba de largo sin usarla nunca.
        //
        // Graba directamente: un botón con un micrófono dibujado que no abre el micrófono es una
        // promesa incumplida, y con 7 años eso se lee como que la app no funciona.
        BotonGrande(
          icono = Icons.Rounded.Mic,
          etiqueta = "Otra vez",
          descripcion = stringResource(R.string.cd_grabar),
          habilitado = tienePermiso,
          tamano = tamano,
          onClick = vm::grabarTarjeta,
        )
        // "Todavía no", nunca "no la sé". La palabra vuelve pronto y no pasa nada.
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.ArrowForward,
          etiqueta = "Todavía no",
          descripcion = "Practicarla otro día",
          tamano = tamano,
          onClick = vm::rendirseConTarjeta,
        )
      }

    is EstadoTarjeta.Repetir ->
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Óyela",
          descripcion = stringResource(R.string.cd_escuchar_de_nuevo),
          color = AzulCalma,
          tamano = tamano,
          onClick = { vm.deletrear(palabra) },
        )
        BotonGrande(
          icono = Icons.Rounded.Mic,
          etiqueta = "Otra vez",
          descripcion = stringResource(R.string.cd_grabar),
          habilitado = tienePermiso,
          tamano = tamano,
          onClick = vm::grabarTarjeta,
        )
      }
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

/** Solo se llega aquí si de verdad no hay ni una palabra guardada todavía. */
@Composable
private fun SinPalabras(vm: MasiViewModel, onLeer: () -> Unit, onVolver: () -> Unit) {
  LaunchedEffect(Unit) {
    vm.leerEnVozAlta("Todavía no hay palabras. Vamos a leer un libro primero.")
  }

  PantallaMasi(barraInferior = { BarraInferior(etiquetaCentro = "Salir", onCentro = onVolver) }) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = "Todavía no hay palabras",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
      )
      Hueco(16)
      Text(
        text = "Lee un libro y aquí van a aparecer las palabras para practicar",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
      )
      Hueco(40)
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.ArrowBack,
          etiqueta = "Volver",
          descripcion = stringResource(R.string.cd_volver),
          color = AzulCalma,
          tamano = tamano,
          onClick = onVolver,
        )
        BotonGrande(
          icono = Icons.Rounded.PhotoCamera,
          etiqueta = "Leer",
          descripcion = stringResource(R.string.cd_leer),
          tamano = tamano,
          onClick = onLeer,
        )
      }
    }
  }
}
