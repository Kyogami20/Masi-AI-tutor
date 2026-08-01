package pe.masi.datos

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "masi_ajustes")

/**
 * Ajustes de la app. Todo local, sin cuenta y sin sincronización.
 *
 * Lo que hay aquí es para el adulto, no para el niño: la pantalla que los edita está detrás de una
 * pulsación larga precisamente para que un niño de 7 años no acabe en ella por accidente.
 */
class Preferencias(private val context: Context) {

  private object Claves {
    val MODO_DEMO = booleanPreferencesKey("modo_demo")
    val OPEN_DYSLEXIC = booleanPreferencesKey("open_dyslexic")
    val ORDEN_CONTENIDO = stringPreferencesKey("orden_contenido")

    /** Lo que el adulto eligió en Ajustes: automático o un reparto GPU/CPU fijo. */
    val PREFERENCIA_MOTOR = stringPreferencesKey("preferencia_motor")

    /**
     * El peldaño al que Masi bajó sola en este teléfono.
     *
     * Se recuerda entre sesiones a propósito: si aquí la GPU no sirve, no va a servir mañana, y
     * reintentarla en cada arranque solo produce dos fotos fallidas antes de volver a bajar.
     */
    val PELDANO_APRENDIDO = stringPreferencesKey("peldano_aprendido")
    // "modo_escucha" existió y se retiró: elegía entre leer por frases o practicar solo las 8
    // palabras más difíciles, y esa segunda opción se saltaba el 95 % del texto sin avisar. La
    // clave puede quedar escrita en el DataStore de quien ya tenga la app; simplemente se ignora.
  }

  /**
   * Respuestas precocinadas en vez de llamar al modelo.
   *
   * Es el seguro de vida del escenario: si el teléfono se calienta, la batería flaquea o el motor
   * falla justo en la demo, se activa esto y el recorrido completo sigue funcionando.
   */
  val modoDemo: Flow<Boolean> =
    context.dataStore.data.map { it[Claves.MODO_DEMO] ?: false }

  /**
   * OpenDyslexic como preferencia personal.
   *
   * No mejora ni la velocidad ni la precisión de lectura — hay estudios — pero que a alguien le
   * guste más es una razón legítima. Por eso está: como opción, nunca como valor por defecto.
   */
  val prefiereOpenDyslexic: Flow<Boolean> =
    context.dataStore.data.map { it[Claves.OPEN_DYSLEXIC] ?: false }

  val preferenciaMotor: Flow<String> =
    context.dataStore.data.map { it[Claves.PREFERENCIA_MOTOR] ?: "AUTOMATICO" }

  val peldanoAprendido: Flow<String?> =
    context.dataStore.data.map { it[Claves.PELDANO_APRENDIDO] }

  suspend fun ponerPreferenciaMotor(valor: String) {
    context.dataStore.edit { it[Claves.PREFERENCIA_MOTOR] = valor }
  }

  suspend fun ponerPeldanoAprendido(valor: String) {
    context.dataStore.edit { it[Claves.PELDANO_APRENDIDO] = valor }
  }

  /** El orden imagen/audio/texto. Existe para poder hacer el A/B sin recompilar. */
  val ordenContenido: Flow<String> =
    context.dataStore.data.map { it[Claves.ORDEN_CONTENIDO] ?: "GALLERY" }

  suspend fun ponerModoDemo(valor: Boolean) {
    context.dataStore.edit { it[Claves.MODO_DEMO] = valor }
  }

  suspend fun ponerOpenDyslexic(valor: Boolean) {
    context.dataStore.edit { it[Claves.OPEN_DYSLEXIC] = valor }
  }

  suspend fun ponerOrdenContenido(valor: String) {
    context.dataStore.edit { it[Claves.ORDEN_CONTENIDO] = valor }
  }
}
