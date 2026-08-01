package pe.masi.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cuándo se degrada un teléfono, probado sin tener el teléfono delante.
 *
 * Es la lógica que arregla el caso más difícil que ha tenido el proyecto —un moto g54 5G donde la
 * GPU corría el texto bien y rompía solo con las fotos— y es exactamente la que no se puede probar a
 * mano, porque haría falta el aparato roto.
 */
class EscaleraDeBackendTest {

  @Test
  fun `los peldanos van de mas rapido a mas compatible`() {
    assertEquals(Peldano.GPU_VISION_CPU, Peldano.TODO_GPU.siguiente())
    assertEquals(Peldano.TODO_CPU, Peldano.GPU_VISION_CPU.siguiente())
    assertNull("abajo del todo ya no hay a dónde bajar", Peldano.TODO_CPU.siguiente())
  }

  @Test
  fun `el peldano intermedio deja el texto en GPU y baja solo la vision`() {
    // La razón de que este peldaño exista: mandarlo todo a CPU funcionaría igual pero sería mucho
    // más lento, y lo que está roto es solo el encoder de visión.
    assertEquals(true, Peldano.GPU_VISION_CPU.textoEnGpu)
    assertEquals(false, Peldano.GPU_VISION_CPU.visionEnGpu)
  }

  @Test
  fun `un solo fallo no degrada nada`() {
    val v = VigilanteDeBackend()
    assertNull(v.anotarFallo())
    assertEquals(Peldano.TODO_GPU, v.peldano)
  }

  @Test
  fun `dos fallos seguidos bajan un peldano`() {
    val v = VigilanteDeBackend()
    v.anotarFallo()
    assertEquals(Peldano.GPU_VISION_CPU, v.anotarFallo())
    assertEquals(Peldano.GPU_VISION_CPU, v.peldano)
  }

  @Test
  fun `una foto ilegible NO acerca la degradacion`() {
    // El caso que hay que proteger: un niño con mal pulso no debe dejar la app en modo lento. Una
    // foto borrosa es el modelo respondiendo BIEN, y no dice nada del acelerador gráfico.
    val v = VigilanteDeBackend()
    v.anotarFallo()
    v.anotarFotoIlegible()
    assertNull("la foto mala tenía que haber reiniciado la cuenta", v.anotarFallo())
    assertEquals(Peldano.TODO_GPU, v.peldano)
  }

  @Test
  fun `una lectura correcta reinicia la cuenta`() {
    val v = VigilanteDeBackend()
    v.anotarFallo()
    v.anotarExito()
    assertNull(v.anotarFallo())
    assertEquals(Peldano.TODO_GPU, v.peldano)
  }

  @Test
  fun `cada peldano nuevo tiene sus propios dos intentos`() {
    val v = VigilanteDeBackend()
    v.anotarFallo()
    v.anotarFallo() // baja al intermedio
    assertNull("el peldaño nuevo merece empezar de cero", v.anotarFallo())
    assertEquals(Peldano.TODO_CPU, v.anotarFallo())
  }

  @Test
  fun `al llegar abajo se deja de bajar`() {
    val v = VigilanteDeBackend(peldanoActual = Peldano.TODO_CPU)
    v.anotarFallo()
    assertNull("no hay peldaño por debajo de CPU", v.anotarFallo())
    assertEquals(Peldano.TODO_CPU, v.peldano)
  }

  @Test
  fun `nunca se sube solo`() {
    // Si en este teléfono la GPU no sirve, no va a servir mañana. Reintentarla en cada arranque
    // solo produce dos fotos fallidas antes de volver a bajar.
    val v = VigilanteDeBackend()
    v.anotarFallo()
    v.anotarFallo()
    repeat(5) { v.anotarExito() }
    assertEquals(Peldano.GPU_VISION_CPU, v.peldano)
  }

  @Test
  fun `fijar a mano manda sobre lo aprendido`() {
    val v = VigilanteDeBackend()
    v.anotarFallo()
    v.anotarFallo()
    v.fijar(Peldano.TODO_GPU)
    assertEquals(Peldano.TODO_GPU, v.peldano)
    // Y la cuenta queda a cero: el peldaño recién elegido merece sus dos intentos.
    assertNull(v.anotarFallo())
  }

  @Test
  fun `las opciones de Ajustes se traducen a peldanos`() {
    assertNull("automático significa que decide Masi", PreferenciaDeMotor.AUTOMATICO.peldanoFijo())
    assertEquals(Peldano.TODO_GPU, PreferenciaDeMotor.FORZAR_GPU.peldanoFijo())
    assertEquals(Peldano.GPU_VISION_CPU, PreferenciaDeMotor.FORZAR_VISION_CPU.peldanoFijo())
    assertEquals(Peldano.TODO_CPU, PreferenciaDeMotor.FORZAR_CPU.peldanoFijo())
  }

  @Test
  fun `el presupuesto se aprieta segun el perfil, pero nunca por debajo del minimo`() {
    val holgado = PresupuestoMasi.de(PerfilDispositivo.HOLGADO)
    val apretado = PresupuestoMasi.de(PerfilDispositivo.APRETADO)

    assertEquals(true, apretado.ladoMaximoFoto < holgado.ladoMaximoFoto)
    assertEquals(false, apretado.precalentarVision)
    assertEquals(true, holgado.precalentarVision)
    // Por debajo de 3072 la transcripción de una página no cabe y sale cortada a media frase, que
    // en pantalla se ve igual que un fallo pero se arregla de otra forma.
    assertEquals(true, apretado.maxTokens >= 3072)
  }

  @Test
  fun `el estado del motor dice el peldano real`() {
    // REGRESIÓN. Antes `aceleracionGpu` estaba escrito a mano como `true`, así que aunque el motor
    // cayera a CPU el estado seguía diciendo GPU. Eso le quitaba a quien depura el único dato útil.
    val listo =
      EstadoMotor.Listo(
        peldano = Peldano.TODO_CPU,
        mtp = false,
        perfil = PerfilDispositivo.APRETADO,
      )
    assertNotNull(listo.peldano)
    assertEquals(false, listo.peldano.textoEnGpu)
  }
}

/**
 * Los umbrales de memoria, que son lo que decide si un teléfono va completo o recortado.
 *
 * No se puede probar `RecursosDispositivo.perfil` sin un `Context`, así que aquí se fija la REGLA
 * —la aritmética— y la lectura del sistema se comprueba en el aparato leyendo el log.
 */
class UmbralesDeMemoriaTest {

  /** La misma regla que aplica `RecursosDispositivo.perfil`, aislada para poder probarla. */
  private fun perfilDe(libres: Double, total: Double, bajaRam: Boolean = false): PerfilDispositivo {
    val grandeConMargen = total >= 5.0 && libres >= 2.2
    return when {
      bajaRam -> PerfilDispositivo.APRETADO
      libres < 1.5 -> PerfilDispositivo.APRETADO
      libres >= 3.6 || grandeConMargen -> PerfilDispositivo.HOLGADO
      else -> PerfilDispositivo.AJUSTADO
    }
  }

  @Test
  fun `el Redmi donde todo funciona NO se degrada`() {
    // REGRESIÓN, y de las caras: la primera versión miraba solo la memoria libre y dejaba el único
    // teléfono probado —"2,4 GB libres de 5,4"— en perfil ajustado, bajándole el contexto y
    // quitándole el precalentado sin ningún motivo.
    assertEquals(PerfilDispositivo.HOLGADO, perfilDe(libres = 2.4, total = 5.4))
  }

  @Test
  fun `un telefono de 4 GB con lo mismo libre SI se recorta`() {
    // Los mismos 2,4 GB libres significan cosas distintas: en un móvil grande buena parte de lo
    // ocupado es caché reclamable; en uno de 4 GB no hay de dónde sacarla.
    assertEquals(PerfilDispositivo.AJUSTADO, perfilDe(libres = 2.4, total = 3.7))
  }

  @Test
  fun `con memoria de sobra siempre va completo`() {
    assertEquals(PerfilDispositivo.HOLGADO, perfilDe(libres = 4.0, total = 4.5))
  }

  @Test
  fun `sin margen se aprieta del todo`() {
    assertEquals(PerfilDispositivo.APRETADO, perfilDe(libres = 1.2, total = 3.7))
  }

  @Test
  fun `un dispositivo marcado de baja RAM se aprieta pase lo que pase`() {
    assertEquals(PerfilDispositivo.APRETADO, perfilDe(libres = 4.0, total = 6.0, bajaRam = true))
  }
}
