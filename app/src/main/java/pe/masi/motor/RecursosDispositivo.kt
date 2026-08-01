package pe.masi.motor

import android.app.ActivityManager
import android.content.Context
import android.util.Log

private const val TAG = "MasiRecursos"

/** Cuánto margen tiene este teléfono. */
enum class PerfilDispositivo(val nombreParaAdulto: String) {
  HOLGADO("completo"),
  AJUSTADO("ligero"),
  APRETADO("mínimo"),
}

/**
 * Los números con los que Masi se adapta al teléfono.
 *
 * Son tres perillas y las tres mueven la aguja de verdad: el contexto dimensiona la caché KV, el
 * lado de la foto decide cuántos megas ocupa un bitmap, y el precalentado provoca un segundo pico de
 * memoria justo después de cargar el modelo.
 */
data class PresupuestoMasi(
  val perfil: PerfilDispositivo,
  /** Tope de contexto. Quedarse corto no da error: **corta la respuesta a media frase**. */
  val maxTokens: Int,
  /** Lado mayor de la foto que se manda al modelo. */
  val ladoMaximoFoto: Int,
  /** Lado mayor que se le pide a la cámara al capturar. */
  val ladoCaptura: Int,
  /** Si conviene cargar el encoder de visión por adelantado. */
  val precalentarVision: Boolean,
  /** Segundos en segundo plano antes de soltar el modelo. */
  val graciaSegundoPlanoMs: Long,
) {
  companion object {
    fun de(perfil: PerfilDispositivo): PresupuestoMasi =
      when (perfil) {
        PerfilDispositivo.HOLGADO ->
          PresupuestoMasi(perfil, 4096, 1024, 2048, precalentarVision = true, 5_000L)

        // Sin precalentado: sería un SEGUNDO pico de memoria a los pocos segundos de cargar el
        // modelo, justo cuando el sistema está peor. Es preferible que la primera foto tarde 20 s
        // más a que la app se cierre antes de llegar a tomarla.
        PerfilDispositivo.AJUSTADO ->
          PresupuestoMasi(perfil, 3072, 896, 1600, precalentarVision = false, 2_000L)

        // 3072 y no menos: por debajo, la transcripción de una página no cabe y sale cortada, que
        // en pantalla se ve igual que un fallo pero se arregla de otra forma.
        PerfilDispositivo.APRETADO ->
          PresupuestoMasi(perfil, 3072, 768, 1200, precalentarVision = false, 2_000L)
      }
  }
}

/**
 * Mide el teléfono para decidir cuánto puede pedirle Masi.
 *
 * La idea y los primeros umbrales vienen de un parche de un compañero del equipo, escrito después de
 * que la app se cerrara en varios teléfonos de 4 GB. Su diagnóstico era correcto y aquí se recoge.
 *
 * **Lo que cambia respecto a aquel parche es que aquí ningún perfil bloquea la carga.** Aquel tenía
 * un `INSUFICIENTE` que se negaba a intentarlo por debajo de 2,9 GB libres, y eso deja fuera
 * teléfonos que sí funcionarían: `availMem` es deliberadamente conservador — no cuenta la caché de
 * páginas que el kernel puede liberar en cuanto alguien se la pida — así que un umbral fijo dice
 * "no cabe" en aparatos donde sí cabe.
 *
 * El criterio de este archivo es otro: **medir, ajustar, avisar y dejar decidir**. Y si el intento
 * de verdad mata la app, la miga de pan de `CajaNegra` lo recuerda y la próxima vez Masi lo
 * desaconseja con evidencia real en vez de con un umbral inventado.
 */
object RecursosDispositivo {

  /** Lo que ocupa el modelo cargado, medido en el Redmi: entre 2,6 y 3,3 GB de memoria nativa. */
  const val GB_QUE_NECESITA_EL_MODELO = 2.6

  /** Con esto libre no hay duda: cabe, sea cual sea el teléfono. */
  private const val GB_LIBRES_SOBRADO = 3.6

  /**
   * Un teléfono grande con margen razonable también va holgado, aunque no llegue a lo anterior.
   *
   * Esta regla existe porque la primera versión no la tenía y **degradaba el único teléfono donde
   * está probado que todo funciona**: el Redmi Note 11 Pro+ mide "2,4 GB libres de 5,4" al abrir la
   * app, y con eso caía a perfil ajustado — bajando el contexto y quitando el precalentado — en un
   * aparato que corre a pleno rendimiento sin problemas.
   *
   * Mirar solo la memoria libre es engañoso: en un móvil de 6 GB, buena parte de lo "ocupado" es
   * caché que el kernel suelta en cuanto alguien la necesita. En uno de 4 GB no hay de dónde sacarla.
   */
  private const val GB_TOTALES_GRANDE = 5.0
  private const val GB_LIBRES_EN_GRANDE = 2.2

  /** Por debajo de esto no hay margen para nada y toca apretar de verdad. */
  private const val GB_LIBRES_MINIMO = 1.5

  data class Memoria(val libresGb: Double, val totalGb: Double, val sistemaJusto: Boolean) {
    fun resumen(): String =
      "%.1f GB libres de %.1f GB%s".format(
        libresGb,
        totalGb,
        if (sistemaJusto) " · el sistema va justo" else "",
      )
  }

  fun memoria(context: Context): Memoria {
    val info = ActivityManager.MemoryInfo()
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am == null) return Memoria(0.0, 0.0, sistemaJusto = true)
    am.getMemoryInfo(info)
    return Memoria(
      libresGb = info.availMem / 1_073_741_824.0,
      totalGb = info.totalMem / 1_073_741_824.0,
      sistemaJusto = info.lowMemory,
    )
  }

  /**
   * El perfil, medido **ahora**.
   *
   * No se cachea a propósito: si el niño acaba de cerrar otras aplicaciones, la respuesta cambia a
   * mejor y Masi debe enterarse. Cachearlo al abrir la app congelaría la peor foto del día.
   */
  fun perfil(context: Context): PerfilDispositivo {
    val m = memoria(context)
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val dispositivoDeBajaRam = am?.isLowRamDevice == true

    val telefonoGrandeConMargen =
      m.totalGb >= GB_TOTALES_GRANDE && m.libresGb >= GB_LIBRES_EN_GRANDE

    val perfil =
      when {
        dispositivoDeBajaRam -> PerfilDispositivo.APRETADO
        m.libresGb < GB_LIBRES_MINIMO -> PerfilDispositivo.APRETADO
        m.libresGb >= GB_LIBRES_SOBRADO || telefonoGrandeConMargen -> PerfilDispositivo.HOLGADO
        else -> PerfilDispositivo.AJUSTADO
      }

    Log.i(TAG, "${m.resumen()} · bajaRam=$dispositivoDeBajaRam → perfil $perfil")
    return perfil
  }

  fun presupuesto(context: Context): PresupuestoMasi = PresupuestoMasi.de(perfil(context))

  /**
   * Si conviene avisar antes de cargar, y con qué palabras.
   *
   * Devuelve null cuando no hay nada que decir. **Nunca impide cargar**: el texto termina invitando
   * a intentarlo igualmente, porque la medición puede equivocarse por lo bajo y quien tiene el
   * teléfono en la mano sabe mejor que nosotros si acaba de cerrar el navegador.
   */
  fun avisoAntesDeCargar(context: Context): String? {
    val m = memoria(context)
    if (m.libresGb >= GB_QUE_NECESITA_EL_MODELO) return null
    return "A este teléfono le quedan %.1f GB libres y Masi necesita unos %.1f. ".format(
      m.libresGb,
      GB_QUE_NECESITA_EL_MODELO,
    ) + "Puede que se cierre. Cierra otras aplicaciones para ir sobre seguro, o inténtalo igual."
  }
}
