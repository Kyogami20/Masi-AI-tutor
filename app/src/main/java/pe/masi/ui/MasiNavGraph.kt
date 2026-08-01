package pe.masi.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pe.masi.ui.pantallas.AjustesScreen
import pe.masi.ui.pantallas.BienvenidaScreen
import pe.masi.ui.pantallas.EscucharScreen
import pe.masi.ui.pantallas.InicioScreen
import pe.masi.ui.pantallas.LeerScreen
import pe.masi.ui.pantallas.TarjetasScreen

/**
 * Las rutas de Masi. Cinco pantallas y ni una más.
 *
 * El "router" del proyecto lo elige el niño con el dedo, no el modelo: por eso no hay aquí ningún
 * framework de agentes. Un framework aporta cuando el modelo debe decidir la secuencia de
 * herramientas por su cuenta, y aquí no lo hace.
 */
object Rutas {
  const val BIENVENIDA = "bienvenida"
  const val INICIO = "inicio"
  const val LEER = "leer"
  const val ESCUCHAR = "escuchar"
  const val TARJETAS = "tarjetas"
  const val AJUSTES = "ajustes"
}

@Composable
fun MasiNavGraph(
  vm: MasiViewModel = viewModel(),
  nav: NavHostController = rememberNavController(),
) {
  NavHost(navController = nav, startDestination = Rutas.BIENVENIDA) {

    composable(Rutas.BIENVENIDA) {
      BienvenidaScreen(
        vm = vm,
        onListo = {
          // La bienvenida se saca de la pila: si el niño vuelve atrás no debe reaparecer.
          nav.navigate(Rutas.INICIO) { popUpTo(Rutas.BIENVENIDA) { inclusive = true } }
        },
      )
    }

    composable(Rutas.INICIO) {
      InicioScreen(
        vm = vm,
        onLeer = {
          vm.reiniciarLeer()
          nav.navigate(Rutas.LEER)
        },
        onTarjetas = { nav.navigate(Rutas.TARJETAS) },
        onAjustes = { nav.navigate(Rutas.AJUSTES) },
      )
    }

    composable(Rutas.LEER) {
      LeerScreen(
        vm = vm,
        onEscuchar = { nav.navigate(Rutas.ESCUCHAR) },
        onVolver = { nav.popBackStack() },
      )
    }

    composable(Rutas.ESCUCHAR) {
      EscucharScreen(
        vm = vm,
        onTerminar = { nav.popBackStack(Rutas.INICIO, inclusive = false) },
      )
    }

    composable(Rutas.TARJETAS) {
      TarjetasScreen(
        vm = vm,
        onVolver = { nav.popBackStack() },
        onLeer = {
          vm.reiniciarLeer()
          nav.navigate(Rutas.LEER)
        },
      )
    }

    composable(Rutas.AJUSTES) { AjustesScreen(vm = vm, onVolver = { nav.popBackStack() }) }
  }
}
