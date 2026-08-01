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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.masi.motor.EstadoMotor
import android.content.Intent
import pe.masi.motor.OrdenContenido
import pe.masi.motor.PreferenciaDeMotor
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
  val preferenciaMotor by vm.preferenciaMotor.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // Se recalcula en cada recomposición a propósito: la memoria libre cambia mientras miras.
  val informe = vm.informeDelTelefono()

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

      // --- Cómo trabaja el motor ---
      //
      // Este selector existe por un caso real: en un moto g54 5G la GPU corría el texto bien y
      // rompía SOLO con las fotos, y la app no daba ninguna forma de arreglarlo. En Automático Masi
      // baja de peldaño sola cuando lo detecta; las opciones fijas son para no depender de eso.
      Text("Cómo trabaja el motor", style = MaterialTheme.typography.bodyMedium)
      Hueco(8)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (opcion in PreferenciaDeMotor.entries) {
          FilterChip(
            selected = preferenciaMotor == opcion,
            onClick = { vm.ponerPreferenciaDeMotor(opcion) },
            label = { Text(etiquetaDeMotor(opcion)) },
          )
        }
      }
      Hueco(8)
      Text(
        "Si Masi no reconoce el libro por más que lo intentes, prueba \"Visión en el procesador\": " +
          "es más lento con las fotos pero funciona en teléfonos donde el acelerador gráfico falla.",
        style = MaterialTheme.typography.labelMedium,
      )

      Hueco(24)
      HorizontalDivider()
      Hueco(24)

      // --- Diagnóstico ---
      //
      // Esta sección es para que alguien del equipo pueda mandar un informe por WhatsApp. Sin ella,
      // un fallo en un teléfono ajeno es "no me funciona" y a partir de ahí se adivina.
      Text("Diagnóstico", style = MaterialTheme.typography.bodyMedium)
      Hueco(8)
      Text(informe, style = MaterialTheme.typography.labelMedium)
      Hueco(8)
      TextButton(
        onClick = {
          val envio =
            Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_SUBJECT, "Masi · informe del teléfono")
              putExtra(Intent.EXTRA_TEXT, informe)
            }
          context.startActivity(Intent.createChooser(envio, "Compartir el informe"))
        }
      ) {
        Text("Compartir este informe")
      }

      // La última escucha, y **solo en pantalla**. No va en el informe de arriba a propósito: ese
      // se manda por WhatsApp y no puede llevar nada que haya dicho un niño.
      //
      // Sirve para una pregunta muy concreta que hasta ahora hacía falta un cable para responder:
      // cuando Masi felicita por una palabra mal leída, ¿es que se tragó el error, o es que el
      // modelo escribió la palabra correcta porque era la que esperaba oír? Son dos averías
      // distintas y desde fuera se ven igual.
      vm.ultimaEscucha()?.let { escucha ->
        Hueco(16)
        Text("Última escucha (no se comparte)", style = MaterialTheme.typography.bodyMedium)
        Hueco(8)
        Text(
          "Decía: \"${escucha.esperado}\"\n" +
            "Se oyó: \"${escucha.transcrito}\"\n" +
            "Masi: ${escucha.decision}",
          style = MaterialTheme.typography.labelMedium,
        )
      }

      Hueco(24)
      HorizontalDivider()
      Hueco(24)

      // La licencia de los pictogramas (CC BY-NC-SA) obliga a atribuir. No es un detalle legal
      // menor: es la condición de uso, y va dentro de la app, no solo en el README.
      Text("Pictogramas", style = MaterialTheme.typography.bodyMedium)
      Hueco(8)
      Text(
        "Autor: Sergio Palao. Origen: ARASAAC (arasaac.org). Licencia: CC BY-NC-SA. " +
          "Propiedad: Gobierno de Aragón.",
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

/** Los nombres del selector, en palabras que un adulto entienda sin saber qué es una GPU. */
private fun etiquetaDeMotor(opcion: PreferenciaDeMotor): String =
  when (opcion) {
    PreferenciaDeMotor.AUTOMATICO -> "Automático (recomendado)"
    PreferenciaDeMotor.FORZAR_GPU -> "Siempre acelerador gráfico"
    PreferenciaDeMotor.FORZAR_VISION_CPU -> "Visión en el procesador"
    PreferenciaDeMotor.FORZAR_CPU -> "Todo en el procesador"
  }
