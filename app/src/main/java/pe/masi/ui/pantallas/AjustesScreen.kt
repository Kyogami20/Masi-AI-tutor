package pe.masi.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.masi.motor.EstadoMotor
import pe.masi.motor.OrdenContenido
import pe.masi.ui.MasiViewModel
import pe.masi.ui.componentes.FondoMasi
import pe.masi.ui.componentes.Hueco

/**
 * Ajustes. Esta pantalla es para el adulto, no para el niño.
 *
 * Por eso aquí sí hay texto pequeño, interruptores y jerga: es la única pantalla de la app que no
 * sigue las reglas de diseño del resto, y es a propósito.
 */
@Composable
fun AjustesScreen(vm: MasiViewModel, onVolver: () -> Unit) {
  val modoDemo by vm.modoDemo.collectAsStateWithLifecycle()
  val openDyslexic by vm.prefiereOpenDyslexic.collectAsStateWithLifecycle()
  val estadoMotor by vm.estadoMotor.collectAsStateWithLifecycle()
  val palabrasSemana by vm.palabrasDeLaSemana.collectAsStateWithLifecycle()
  val total by vm.totalTarjetas.collectAsStateWithLifecycle()
  val orden by vm.ordenContenido.collectAsStateWithLifecycle()

  FondoMasi(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
    ) {
      Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
      Hueco(24)

      // --- El resumen para la familia ---
      Text("Cómo va", style = MaterialTheme.typography.bodyMedium)
      Hueco(8)
      // Nunca se etiqueta al niño. Ni "nivel bajo", ni "tiene dificultades", ni un semáforo. Solo
      // cuánto ha practicado.
      Text(
        "Esta semana practicó $palabrasSemana palabras. En total está practicando $total.",
        style = MaterialTheme.typography.labelMedium,
      )
      Hueco(8)
      Text(
        "Masi no diagnostica nada. Si notas que una dificultad se repite mucho, conversa con " +
          "la profesora de tu hijo o hija.",
        style = MaterialTheme.typography.labelMedium,
      )

      Hueco(24)
      HorizontalDivider()
      Hueco(24)

      // Aquí hubo un selector "Por frases / Palabra por palabra". Se retiró porque el segundo modo
      // no hacía lo que su nombre decía: en vez de recorrer el texto palabra a palabra, lo
      // sustituía por las 8 palabras más difíciles y descartaba el resto de la página. Ahora la
      // lectura es siempre por fragmentos y siempre cubre el texto entero; practicar una palabra
      // suelta sigue existiendo donde tiene sentido, al fallarla y en las tarjetas.

      // --- Letra ---
      Fila(
        titulo = "Usar la letra OpenDyslexic",
        detalle =
          "Los estudios no encuentran mejora en velocidad ni en precisión frente a una letra " +
            "normal. Está aquí porque a algunos niños les gusta más, y esa es razón suficiente.",
        marcado = openDyslexic,
        onCambio = vm::ponerOpenDyslexic,
      )

      Hueco(24)
      HorizontalDivider()
      Hueco(24)

      // --- Modo demo ---
      Fila(
        titulo = "Modo demostración",
        detalle =
          "Responde con textos fijos, sin usar el modelo. Sirve para enseñar la app sin " +
            "esperar y para salir del paso si el teléfono va justo.",
        marcado = modoDemo,
        onCambio = vm::ponerModoDemo,
      )

      Hueco(24)
      HorizontalDivider()
      Hueco(24)

      // --- Experimento del orden de contenido ---
      Text("Orden del contenido (experimento)", style = MaterialTheme.typography.bodyMedium)
      Hueco(8)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = orden == OrdenContenido.GALLERY,
          onClick = { vm.ponerOrdenContenido(OrdenContenido.GALLERY) },
          label = { Text("imagen · audio · texto") },
        )
        FilterChip(
          selected = orden == OrdenContenido.MODEL_CARD,
          onClick = { vm.ponerOrdenContenido(OrdenContenido.MODEL_CARD) },
          label = { Text("imagen · texto · audio") },
        )
      }
      Hueco(8)
      Text(
        "El model card de Gemma 4 y el código de Google AI Edge Gallery discrepan en esto. " +
          "Cambiarlo aquí permite comparar sin recompilar.",
        style = MaterialTheme.typography.labelMedium,
      )

      Hueco(24)
      HorizontalDivider()
      Hueco(24)

      Text("Estado del motor", style = MaterialTheme.typography.bodyMedium)
      Hueco(8)
      Text(
        when (val e = estadoMotor) {
          is EstadoMotor.Listo ->
            "Encendido. GPU: ${if (e.aceleracionGpu) "sí" else "no"} · MTP: ${if (e.mtp) "sí" else "no"}"
          is EstadoMotor.Cargando -> "Encendiendo…"
          is EstadoMotor.SinModelo -> "Sin modelo cargado"
          is EstadoMotor.Error -> "Error: ${e.mensaje}"
        },
        style = MaterialTheme.typography.labelMedium,
      )

      Hueco(32)
      Text(
        "Masi funciona sin conexión. Ni el audio ni las fotos salen de este teléfono: no hay " +
          "servidor al que pudieran ir.",
        style = MaterialTheme.typography.labelMedium,
      )

      Hueco(24)
      TextButton(onClick = onVolver, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Volver")
      }
      Hueco(24)
    }
  }
}

@Composable
private fun Fila(titulo: String, detalle: String, marcado: Boolean, onCambio: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(titulo, style = MaterialTheme.typography.bodyMedium)
      Hueco(4)
      Text(detalle, style = MaterialTheme.typography.labelMedium)
    }
    Switch(checked = marcado, onCheckedChange = onCambio)
  }
}
