package pe.masi.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.masi.R
import pe.masi.ui.MasiViewModel
import pe.masi.ui.componentes.BotonGrande
import pe.masi.ui.componentes.FondoMasi
import pe.masi.ui.componentes.Hueco
import pe.masi.ui.theme.VerdeLogro

/**
 * La pantalla de casa. Dos botones enormes y nada más.
 *
 * Cero texto instruccional: al entrar, Masi dice en voz alta lo que se puede hacer. El acceso a
 * ajustes es un icono pequeño en la esquina y se abre con pulsación larga, para que un niño no
 * acabe ahí por accidente.
 */
@Composable
fun InicioScreen(
  vm: MasiViewModel,
  onLeer: () -> Unit,
  onTarjetas: () -> Unit,
  onAjustes: () -> Unit,
) {
  val pendientes by vm.cola.collectAsStateWithLifecycle()
  val palabrasSemana by vm.palabrasDeLaSemana.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    vm.cargarRepaso()
    vm.leerEnVozAlta("Hola. ¿Quieres leer un libro, o practicar tus palabras?")
  }

  FondoMasi(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(32.dp),
          verticalAlignment = Alignment.Top,
        ) {
          BotonGrande(
            icono = Icons.Rounded.PhotoCamera,
            etiqueta = "Leer",
            descripcion = stringResource(R.string.cd_leer),
            onClick = {
              vm.callar()
              onLeer()
            },
          )
          BotonGrande(
            icono = Icons.Rounded.Style,
            etiqueta = if (pendientes.isEmpty()) "Practicar" else "Practicar (${pendientes.size})",
            descripcion = stringResource(R.string.cd_tarjetas),
            color = VerdeLogro,
            onClick = {
              vm.callar()
              onTarjetas()
            },
          )
        }

        Hueco(48)

        // Lenguaje de progreso, siempre. Nunca "nivel bajo", nunca un semáforo en rojo, nunca una
        // etiqueta sobre el niño. Un niño que se cree tonto deja de intentarlo.
        if (palabrasSemana > 0) {
          Text(
            text = "Esta semana practicaste $palabrasSemana palabras",
            style = MaterialTheme.typography.bodyMedium,
            color = VerdeLogro,
            textAlign = TextAlign.Center,
          )
        }
      }

      IconButton(
        onClick = onAjustes,
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp),
      ) {
        Icon(
          Icons.Rounded.Settings,
          contentDescription = stringResource(R.string.cd_ajustes),
          tint = MaterialTheme.colorScheme.outline,
        )
      }
    }
  }
}
