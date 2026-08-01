package pe.masi.ui.pantallas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.masi.ui.EstadoArranque
import pe.masi.ui.MasiViewModel
import pe.masi.ui.componentes.BarraProgreso
import pe.masi.ui.componentes.EstadoCargando
import pe.masi.ui.componentes.FondoMasi
import pe.masi.ui.componentes.Hueco
import pe.masi.descarga.ImportadorSAF

/**
 * La primera pantalla. Tapa el arranque del motor y resuelve la llegada del modelo.
 *
 * Los ~10 segundos que tarda `engine.initialize()` no son un mal rato que aguantar: son el rato en
 * que Masi se presenta. La animación de bienvenida es una necesidad técnica disfrazada de diseño.
 */
@Composable
fun BienvenidaScreen(vm: MasiViewModel, onListo: () -> Unit) {
  val arranque by vm.arranque.collectAsStateWithLifecycle()

  val selectorArchivo =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) vm.importarModelo(uri)
    }

  LaunchedEffect(Unit) { vm.comprobarModelo() }
  LaunchedEffect(arranque) { if (arranque is EstadoArranque.Listo) onListo() }

  FondoMasi(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = "Masi",
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Hueco(8)
      Text(
        text = "tu compañero de lectura",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
      )
      Hueco(48)

      when (val estado = arranque) {
        is EstadoArranque.Comprobando -> EstadoCargando("Preparando todo…")

        is EstadoArranque.Encendiendo ->
          // Aquí es donde se van los 10 segundos de initialize().
          EstadoCargando("Masi se está despertando…")

        is EstadoArranque.Listo -> EstadoCargando("¡Ya casi!")

        is EstadoArranque.FaltaModelo -> FaltaModelo(vm, selectorArchivo::launch)

        is EstadoArranque.Trayendo -> {
          Text(
            text = "Trayendo el cerebro de Masi",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
          )
          Hueco(24)
          BarraProgreso(estado.fraccion)
          Hueco(12)
          Text(
            text = "${enGigas(estado.recibidos)} de ${enGigas(estado.totales)}",
            style = MaterialTheme.typography.labelMedium,
          )
          Hueco(8)
          Text(
            text = "Solo hay que hacerlo una vez. Después, Masi funciona sin internet.",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
          )
          Hueco(24)
          TextButton(onClick = vm::cancelarDescarga) { Text("Pausar") }
        }

        is EstadoArranque.NoSePudo -> {
          Text(
            text = "No pude terminar",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
          )
          Hueco(12)
          Text(
            text = estado.mensaje,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
          )
          Hueco(24)
          // Lo descargado se conserva: reintentar reanuda, no vuelve a empezar.
          Button(onClick = vm::descargarModelo) { Text("Seguir intentando") }
          Hueco(8)
          TextButton(onClick = { selectorArchivo.launch(ImportadorSAF.TIPOS) }) {
            Text("Buscar el archivo en el teléfono")
          }
        }
      }
    }
  }
}

@Composable
private fun FaltaModelo(vm: MasiViewModel, abrirSelector: (Array<String>) -> Unit) {
  Text(
    text = "Falta el cerebro de Masi",
    style = MaterialTheme.typography.headlineMedium,
    textAlign = TextAlign.Center,
  )
  Hueco(12)
  Text(
    text =
      "Es un archivo de unos 2,6 gigas. Se descarga una sola vez y después Masi funciona " +
        "siempre sin internet, incluso en modo avión.",
    style = MaterialTheme.typography.labelMedium,
    textAlign = TextAlign.Center,
  )
  Hueco(32)
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Button(onClick = vm::descargarModelo, modifier = Modifier.weight(1f)) { Text("Descargar") }
    TextButton(
      onClick = { abrirSelector(ImportadorSAF.TIPOS) },
      modifier = Modifier.weight(1f),
    ) {
      Text("Ya lo tengo")
    }
  }
}

private fun enGigas(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)
