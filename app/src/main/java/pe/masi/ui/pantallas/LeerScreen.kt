package pe.masi.ui.pantallas

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.masi.R
import pe.masi.media.ControlCamara
import pe.masi.media.VistaCamara
import pe.masi.ui.EstadoLeer
import pe.masi.ui.MasiViewModel
import pe.masi.ui.componentes.BarraInferior
import pe.masi.ui.componentes.BotonGrande
import pe.masi.ui.componentes.EstadoCargando
import pe.masi.ui.componentes.FilaDeBotones
import pe.masi.ui.componentes.Hueco
import pe.masi.ui.componentes.MarcoGuia
import pe.masi.ui.componentes.MarcoPorDefecto
import pe.masi.ui.componentes.PantallaMasi
import pe.masi.ui.componentes.RecorteImagen
import pe.masi.ui.componentes.TextoAdaptado
import pe.masi.ui.theme.AzulCalma
import pe.masi.ui.theme.VerdeLogro

/**
 * Pantalla de Leer: fotografía la página y muestra el texto adaptado.
 *
 * Es la más lenta de la app con diferencia, y por eso tiene dos cosas que las demás no: un paso de
 * confirmación con recorte —para no esperar un minuto por una foto movida— y un contador de
 * palabras durante la espera.
 */
@Composable
fun LeerScreen(vm: MasiViewModel, onEscuchar: () -> Unit, onVolver: () -> Unit) {
  val estado by vm.leer.collectAsStateWithLifecycle()
  val modoDemo by vm.modoDemo.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val control = remember { ControlCamara() }

  var tienePermiso by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val pedirPermiso =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      tienePermiso = it
    }

  LaunchedEffect(Unit) { if (!tienePermiso) pedirPermiso.launch(Manifest.permission.CAMERA) }

  when (val e = estado) {
    is EstadoLeer.Encuadrando ->
      Encuadre(
        vm = vm,
        tienePermiso = tienePermiso || modoDemo,
        control = control,
        modoDemo = modoDemo,
        onVolver = onVolver,
      )

    is EstadoLeer.Confirmando -> Confirmacion(vm, e, onVolver)

    is EstadoLeer.Mirando ->
      PantallaMasi {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          EstadoCargando(
            mensaje = "Masi está mirando tu libro…",
            // Ver la cifra subir es lo que distingue "está trabajando" de "se colgó". La espera
            // sigue siendo de un minuto; lo que cambia es que se nota que avanza.
            detalle = if (e.palabras > 0) "${e.palabras} palabras leídas" else "Un momentito…",
          )
        }
      }

    is EstadoLeer.Leida -> PaginaLista(vm, e, onEscuchar, onVolver)

    is EstadoLeer.Repetir -> Reintento(vm, onVolver)
  }
}

@Composable
private fun Encuadre(
  vm: MasiViewModel,
  tienePermiso: Boolean,
  control: ControlCamara,
  modoDemo: Boolean,
  onVolver: () -> Unit,
) {
  LaunchedEffect(Unit) {
    vm.leerEnVozAlta("Apunta a la página del libro y toca el botón grande. Puedes girar el teléfono.")
  }

  PantallaMasi(
    barraInferior = { BarraInferior(etiquetaCentro = "Salir", onCentro = onVolver) }
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      if (tienePermiso && !modoDemo) {
        VistaCamara(control = control, modifier = Modifier.fillMaxSize())

        // El marco, dibujado de verdad y con las mismas proporciones que el recorte por defecto.
        //
        // Antes decía "pon la página dentro del marco" sin pintar ningún marco: la instrucción se
        // refería a algo que no existía. Ahora, además, sí sirve para algo: es la zona que se va a
        // recortar.
        MarcoGuia(modifier = Modifier.fillMaxSize())
        Text(
          text = "Pon la página dentro del marco",
          style = MaterialTheme.typography.bodyMedium,
          color = Color.White,
          textAlign = TextAlign.Center,
          modifier =
            Modifier.align(Alignment.TopCenter)
              .padding(top = 16.dp)
              .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
              .padding(horizontal = 16.dp, vertical = 8.dp),
        )
      } else {
        Column(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = if (modoDemo) "Modo demostración" else "Masi necesita ver el libro",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
          )
        }
      }

      Box(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
        contentAlignment = Alignment.Center,
      ) {
        BotonGrande(
          icono = Icons.Rounded.PhotoCamera,
          etiqueta = "Tomar la foto",
          descripcion = stringResource(R.string.cd_disparador),
          onClick = {
            vm.callar()
            if (modoDemo) vm.fotoTomada(ByteArray(1)) else control.disparar(vm::fotoTomada)
          },
        )
      }
    }
  }
}

/**
 * La foto tomada, con el recorte ajustable.
 *
 * Tres cosas de una vez: el niño ve lo que capturó y puede repetirlo antes de esperar un minuto; el
 * recorte concentra el presupuesto de tokens del encoder de visión en el texto y no en la mesa; y
 * quien depura ve de un vistazo si la foto era legible.
 */
@Composable
private fun Confirmacion(vm: MasiViewModel, estado: EstadoLeer.Confirmando, onVolver: () -> Unit) {
  // El ImageBitmap se recuerda, no se deriva en cada recomposición.
  //
  // `asImageBitmap()` construye un objeto NUEVO cada vez que se llama. Como `RecorteImagen` usa esa
  // imagen como clave de su `pointerInput`, un objeto nuevo en cada recomposición reiniciaba el
  // bloque de gestos —y abortaba el arrastre en curso— cada vez que el recorte cambiaba. Ese era
  // medio problema de "se mueve a saltos"; la otra mitad está en RecorteImagen.kt.
  val imagen =
    remember(estado) {
      runCatching { BitmapFactory.decodeByteArray(estado.fotoPng, 0, estado.fotoPng.size) }
        .getOrNull()
        ?.asImageBitmap()
    }
  var recorte by remember(estado) { mutableStateOf(MarcoPorDefecto.relativo()) }

  LaunchedEffect(Unit) {
    vm.leerEnVozAlta("¿Se ve bien la página? Puedes mover las esquinas.")
  }

  PantallaMasi(
    encabezado = "Mueve las esquinas para dejar solo el texto",
    barraInferior = { BarraInferior(etiquetaCentro = "Salir", onCentro = onVolver) },
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (imagen != null) {
          RecorteImagen(
            imagen = imagen,
            recorte = recorte,
            onRecorteCambiado = { recorte = it },
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          // En modo demostración no hay foto de verdad que enseñar.
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = "Página de ejemplo",
              style = MaterialTheme.typography.headlineMedium,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
      Hueco(16)
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.Rounded.Refresh,
          etiqueta = "Otra foto",
          descripcion = "Volver a tomar la foto",
          color = AzulCalma,
          tamano = tamano,
          onClick = {
            vm.callar()
            vm.reiniciarLeer()
          },
        )
        BotonGrande(
          icono = Icons.Rounded.Check,
          etiqueta = "¡Así está!",
          descripcion = "Usar esta foto",
          color = VerdeLogro,
          tamano = tamano,
          onClick = {
            vm.callar()
            vm.confirmarFoto(if (imagen != null) recorte else null)
          },
        )
      }
      Hueco(12)
    }
  }
}

@Composable
private fun Reintento(vm: MasiViewModel, onVolver: () -> Unit) {
  val mensaje = "Vamos a tomar la foto otra vez. Acerca un poquito el libro."

  // Todo mensaje se locuta. El niño no puede leer esto: es literalmente por lo que existe la app.
  LaunchedEffect(Unit) { vm.leerEnVozAlta(mensaje) }

  PantallaMasi(barraInferior = { BarraInferior(etiquetaCentro = "Salir", onCentro = onVolver) }) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      // Ni "error" ni "falló": la foto se repite y ya está. El niño no hizo nada mal.
      Text(
        text = "Vamos a tomar la foto otra vez",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
      )
      Hueco(16)
      Text(
        text = "Acerca un poquito el libro y que se vea toda la página",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
      )
      Hueco(40)
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Repítelo",
          descripcion = stringResource(R.string.cd_escuchar_de_nuevo),
          color = AzulCalma,
          tamano = tamano,
          onClick = { vm.leerEnVozAlta(mensaje) },
        )
        BotonGrande(
          icono = Icons.Rounded.PhotoCamera,
          etiqueta = "Otra vez",
          descripcion = stringResource(R.string.cd_leer),
          tamano = tamano,
          onClick = {
            vm.callar()
            vm.reiniciarLeer()
          },
        )
      }
    }
  }
}

@Composable
private fun PaginaLista(
  vm: MasiViewModel,
  estado: EstadoLeer.Leida,
  onEscuchar: () -> Unit,
  onVolver: () -> Unit,
) {
  LaunchedEffect(estado) { vm.leerEnVozAlta("Ya está. ¿Lo leemos juntos?") }

  PantallaMasi(barraInferior = { BarraInferior(etiquetaCentro = "Salir", onCentro = onVolver) }) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      // El texto va en su propia zona con scroll: por larga que sea la página, nunca empuja a los
      // botones fuera de la pantalla.
      Column(
        modifier =
          Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)
      ) {
        // Se muestra el texto silabado: la separación en sílabas sí tiene evidencia a favor.
        TextoAdaptado(texto = estado.lectura.silabas.ifBlank { estado.lectura.texto })
      }
      Hueco(12)
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Escúchalo",
          descripcion = stringResource(R.string.cd_escuchar_de_nuevo),
          color = AzulCalma,
          tamano = tamano,
          onClick = { vm.leerEnVozAlta(estado.lectura.texto) },
        )
        BotonGrande(
          icono = Icons.Rounded.Mic,
          etiqueta = "Ahora leo yo",
          descripcion = stringResource(R.string.cd_escuchar),
          tamano = tamano,
          onClick = {
            vm.callar()
            onEscuchar()
          },
        )
      }
      Hueco(12)
    }
  }
}
