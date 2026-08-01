package pe.masi.ui.pantallas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.masi.datos.Cuento
import pe.masi.servicios.PasoCuento
import pe.masi.ui.EstadoCuentos
import pe.masi.ui.MasiViewModel
import pe.masi.ui.componentes.BarraInferior
import pe.masi.ui.componentes.BotonGrande
import pe.masi.ui.componentes.EstadoCargando
import pe.masi.ui.componentes.FilaDeBotones
import pe.masi.ui.componentes.Hueco
import pe.masi.ui.componentes.PantallaMasi
import pe.masi.ui.componentes.TextoAdaptado
import pe.masi.ui.theme.AzulCalma
import pe.masi.ui.theme.CremaOscura
import pe.masi.ui.theme.Terracota
import pe.masi.ui.theme.VerdeLogro

/**
 * Los cuentos que Masi escribe con las palabras que al niño le cuestan.
 *
 * **Es el único recorrido de la app donde el modelo decide por sí mismo qué hacer.** No recibe las
 * palabras en el prompt: las pide con una herramienta, escribe el cuento, comprueba con otra
 * herramienta si de verdad las incluyó —comprobación determinista, no autoevaluación— y reescribe si
 * le faltan. Ese bucle lo ejecuta el runtime de LiteRT-LM con el function calling nativo de Gemma 4.
 *
 * Y cierra una promesa del producto: Masi no solo lee los textos que encuentra, **genera** material
 * a partir de lo que al niño le está costando. Leer una palabra dentro de una historia enseña mucho
 * más que verla suelta en una tarjeta.
 */
@Composable
fun CuentosScreen(vm: MasiViewModel, onVolver: () -> Unit, onLeerCuento: () -> Unit) {
  val estado by vm.estadoCuentos.collectAsStateWithLifecycle()
  val cuentos by vm.cuentos.collectAsStateWithLifecycle()
  val tarjetas by vm.cola.collectAsStateWithLifecycle()

  when (val e = estado) {
    is EstadoCuentos.Biblioteca ->
      Biblioteca(
        cuentos = cuentos,
        puedeEscribir = tarjetas.size >= MIN_TARJETAS,
        onEscribir = vm::escribirCuento,
        onAbrir = vm::abrirCuento,
        onVolver = onVolver,
      )

    is EstadoCuentos.Escribiendo -> Escribiendo(e.paso, e.parcial, vm::volverALaBiblioteca)

    is EstadoCuentos.Listo ->
      CuentoCompleto(
        cuento = e.cuento,
        onLeer = onLeerCuento,
        onOir = { vm.leerEnVozAlta(e.cuento.texto) },
        onBorrar = { vm.borrarCuento(e.cuento) },
        onVolver = vm::volverALaBiblioteca,
      )

    is EstadoCuentos.NoSirve -> Aviso(e.mensaje, vm::escribirCuento, vm::volverALaBiblioteca)

    is EstadoCuentos.SinPalabras ->
      Aviso(
        "Todavía no hay palabras para el cuento. Lee un libro primero.",
        null,
        vm::volverALaBiblioteca,
      )
  }
}

/** Con menos de dos palabras guardadas no hay nada que practicar dentro de un cuento. */
private const val MIN_TARJETAS = 2

@Composable
private fun Biblioteca(
  cuentos: List<Cuento>,
  puedeEscribir: Boolean,
  onEscribir: () -> Unit,
  onAbrir: (Cuento) -> Unit,
  onVolver: () -> Unit,
) {
  PantallaMasi(
    encabezado = if (cuentos.isEmpty()) null else "${cuentos.size} cuentos tuyos",
    barraInferior = { BarraInferior(etiquetaCentro = "Salir", onCentro = onVolver) },
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (cuentos.isEmpty()) {
          Text(
            text =
              if (puedeEscribir) "Masi puede escribirte un cuento con tus palabras"
              else "Lee un libro primero y después Masi te escribirá un cuento",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
          )
        } else {
          LazyColumn(
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(cuentos, key = { it.id }) { cuento -> CartaCuento(cuento) { onAbrir(cuento) } }
          }
        }
      }

      FilaDeBotones(cuantos = 1) { tamano ->
        BotonGrande(
          icono = Icons.Rounded.AutoAwesome,
          etiqueta = "Un cuento nuevo",
          descripcion = "Masi escribe un cuento con tus palabras",
          color = Terracota,
          habilitado = puedeEscribir,
          tamano = tamano,
          onClick = onEscribir,
        )
      }
      Hueco(12)
    }
  }
}

/** Una carta ancha: el título manda, y debajo asoma cómo empieza. */
@Composable
private fun CartaCuento(cuento: Cuento, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, CremaOscura),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = cuento.titulo,
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp),
        maxLines = 2,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Text(
        text = cuento.texto.take(70).trim() + "…",
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        color = MaterialTheme.colorScheme.outline,
      )
      if (cuento.palabras.isNotEmpty()) {
        Text(
          text = "practica: ${cuento.palabras.joinToString(", ")}",
          style = MaterialTheme.typography.labelSmall,
          color = VerdeLogro,
        )
      }
    }
  }
}

/**
 * Casi un minuto de espera, y **no hay texto que enseñar**.
 *
 * Cuando el bucle agéntico va bien el modelo no emite ni una palabra suelta: el cuento entero viaja
 * dentro de los argumentos de las llamadas a herramienta. Así que se enseña por dónde va, que
 * además deja ver el bucle funcionando.
 */
@Composable
private fun Escribiendo(paso: PasoCuento, parcial: String, onCancelar: () -> Unit) {
  val mensaje =
    when (paso) {
      PasoCuento.ELIGIENDO_PALABRAS -> "Masi está eligiendo tus palabras…"
      PasoCuento.ESCRIBIENDO -> "Masi está escribiendo tu cuento…"
      // "Mejorando" y no "revisando": el reintento solo salta cuando no entró ninguna palabra, y el
      // primer cuento se queda en pantalla mientras tanto.
      PasoCuento.REVISANDO -> "Masi está mejorando el cuento…"
      PasoCuento.GUARDANDO -> "Ya casi está…"
    }

  PantallaMasi(
    barraInferior = { BarraInferior(etiquetaCentro = "Cancelar", onCentro = onCancelar) }
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      EstadoCargando(mensaje, detalle = "Esto tarda un poquito")
      Hueco(20)
      // Ahora el cuento vuelve a llegar como texto, así que se enseña mientras se escribe: ver
      // letras aparecer es la diferencia entre "está pensando" y "se colgó".
      if (parcial.isNotBlank()) {
        Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
          Text(
            text = parcial,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
          )
        }
      }
    }
  }
}

@Composable
private fun CuentoCompleto(
  cuento: Cuento,
  onLeer: () -> Unit,
  onOir: () -> Unit,
  onBorrar: () -> Unit,
  onVolver: () -> Unit,
) {
  var confirmarBorrado by remember { mutableStateOf(false) }

  PantallaMasi(
    encabezado = cuento.titulo,
    accionEncabezado = {
      TextButton(onClick = { confirmarBorrado = true }) {
        Text(
          "Quitar",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.outline,
        )
      }
    },
    barraInferior = { BarraInferior(etiquetaCentro = "Mis cuentos", onCentro = onVolver) },
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // Silabado, igual que una página fotografiada: la separación en sílabas sí tiene evidencia.
        TextoAdaptado(texto = cuento.texto)
      }
      FilaDeBotones(cuantos = 2) { tamano ->
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.VolumeUp,
          etiqueta = "Óyelo",
          descripcion = "Masi te lee el cuento",
          color = AzulCalma,
          tamano = tamano,
          onClick = onOir,
        )
        BotonGrande(
          icono = Icons.AutoMirrored.Rounded.MenuBook,
          etiqueta = "Léelo tú",
          descripcion = "Leer el cuento en voz alta",
          color = VerdeLogro,
          tamano = tamano,
          onClick = onLeer,
        )
      }
      Hueco(12)
    }
  }

  if (confirmarBorrado) {
    AlertDialog(
      onDismissRequest = { confirmarBorrado = false },
      title = { Text("¿Quitar este cuento?") },
      text = {
        Text(
          "Se borrará de tus cuentos. Masi puede escribirte otro cuando quieras.",
          style = MaterialTheme.typography.labelMedium,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmarBorrado = false
            onBorrar()
          }
        ) {
          Text("Quitar")
        }
      },
      dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") } },
    )
  }
}

/** Nada de culpar al niño: el cuento no salió, se prueba otra vez y ya. */
@Composable
private fun Aviso(mensaje: String, onReintentar: (() -> Unit)?, onVolver: () -> Unit) {
  PantallaMasi(
    barraInferior = { BarraInferior(etiquetaCentro = "Mis cuentos", onCentro = onVolver) }
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = mensaje,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
      )
      if (onReintentar != null) {
        Hueco(40)
        FilaDeBotones(cuantos = 1) { tamano ->
          BotonGrande(
            icono = Icons.Rounded.Refresh,
            etiqueta = "Otra vez",
            descripcion = "Intentar escribir el cuento de nuevo",
            color = Terracota,
            tamano = tamano,
            onClick = onReintentar,
          )
        }
      }
    }
  }
}
