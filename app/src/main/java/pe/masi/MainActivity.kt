package pe.masi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.masi.ui.MasiNavGraph
import pe.masi.ui.MasiViewModel
import pe.masi.ui.theme.TemaMasi

/**
 * La única Activity de la app.
 *
 * La pantalla se mantiene encendida mientras Masi está en primer plano: un niño puede tardar un
 * rato en decidirse a leer en voz alta, y que se apague la pantalla a mitad de una grabación sería
 * el peor momento posible.
 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    setContent {
      val vm: MasiViewModel = viewModel()
      val openDyslexic by vm.prefiereOpenDyslexic.collectAsStateWithLifecycle()
      TemaMasi(prefiereOpenDyslexic = openDyslexic) { MasiNavGraph(vm = vm) }
    }
  }
}
