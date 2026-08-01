# Masi

**Compañero de lectura 100 % offline, con Gemma 4 E2B ejecutándose dentro del teléfono, para niños
con dificultades de lectoescritura en el Perú.**

*Masi* significa "compañero, amigo" en quechua.

Masi hace tres cosas, y solo tres:

1. **Ve.** El niño fotografía la página de su libro. Gemma 4 extrae el texto y lo devuelve adaptado:
   espaciado amplio, sílabas separadas.
2. **Escucha.** El niño lee en voz alta, oración a oración hasta terminar la página. Masi compara
   lo que dijo contra lo que estaba escrito y detecta qué palabras falló.
3. **Acompaña.** Le explica el error con amabilidad y una pista concreta, y guarda esa palabra para
   practicarla otro día — leyéndola de nuevo en voz alta, no marcando una casilla.

**No hay nube, no hay servidor, no hay red.** El modelo vive en el almacenamiento del teléfono y la
app funciona íntegramente en modo avión. La voz de un niño no sale del dispositivo porque no hay
ningún sitio al que pudiera salir.

---

## Cómo funciona por dentro

```
┌─────────────────────────────────────────────────────────────┐
│  UN SOLO PROCESO — la app de Masi en el teléfono            │
│                                                             │
│  ui/            Jetpack Compose · cámara · micro · voz      │
│         │                                                   │
│  servicios/     LectorService   imagen → Lectura            │
│                 EscuchaService  audio  → transcripción      │
│                 TutorService    error  → pista amable       │
│                 EvaluadorPronunciacion  audio → veredicto   │
│                 DetectorErrores + PoliticaConservadora      │
│                 Segmentador · Silabas · RepasoService       │
│         │                                                   │
│  motor/         MotorMasi → LiteRT-LM (Engine·Conversation) │
│                 GPU (texto/visión) + CPU (audio)            │
│                                                             │
│  datos/         Room — tarjetas y progreso, en el aparato   │
└─────────────────────────────────────────────────────────────┘
                              ▲ lee (no ejecuta)
                  ┌───────────┴────────────┐
                  │  gemma-4-E2B-it        │
                  │  .litertlm  (~2,6 GB)  │
                  └────────────────────────┘
```

Tres agentes = tres `Conversation` sobre un único `Engine`: un modelo en RAM, tres personalidades
con su propio system prompt y su propia temperatura. Se mantiene **una conversación viva a la vez**
y se recrea al cambiar de rol; recrear no recarga el modelo, solo reinicia la caché KV, y en un
teléfono de 6 GB tres cachés simultáneas compiten por memoria que no sobra.

| Agente | Temperatura | Por qué |
|---|---|---|
| LECTOR | 0.2 | Transcribir es determinista. Creatividad = alucinación |
| ESCUCHA | 0.1 | Aún más estricto: aquí la creatividad es el enemigo |
| TUTOR | 0.7 | Aquí sí queremos variedad, que no repita la misma frase |

### Lo difícil: el audio

La intuición dice que basta con transcribir y comparar. **No funciona por defecto**, y la razón es
interesante: un ASR está entrenado para producir la transcripción *más probable*, así que cuando el
niño lee "El bero corre" el modelo oye algo ambiguo en un contexto donde "perro" es abrumadoramente
más probable — y escribe "perro". **Corrige exactamente el error que hay que detectar.**

Cuatro capas de mitigación, y dónde vive cada una:

| Capa | Qué hace | Dónde |
|---|---|---|
| 1 | Prompt que fuerza transcripción literal, con el texto esperado en la primera línea | [`Prompts.kt`](app/src/main/java/pe/masi/motor/Prompts.kt) |
| 2 | Fragmentos de 10 palabras como mucho, y clips de 12 s (3 s si es una palabra suelta), nunca 30 | [`Segmentador.kt`](app/src/main/java/pe/masi/servicios/Segmentador.kt) · [`GrabadorAudio.kt`](app/src/main/java/pe/masi/media/GrabadorAudio.kt) |
| 3 | Comparación en código determinista, nunca en el modelo | [`DetectorErrores.kt`](app/src/main/java/pe/masi/servicios/DetectorErrores.kt) |
| 4 | Umbral conservador: ante la duda, no se marca error | [`PoliticaConservadora.kt`](app/src/main/java/pe/masi/servicios/PoliticaConservadora.kt) |

La capa 4 es el corazón ético del proyecto. Marcar como fallo la lectura correcta de un niño le
enseña que es malo leyendo, y ese daño dura años. Además, el español andino tiene realizaciones
vocálicas distintas del limeño, así que un modelo puede marcar como error de lectura lo que es
simplemente el acento del niño. Por eso: **es preferible dejar pasar un error real a inventar uno
falso.**

### Lo que dice la evidencia sobre las fuentes para dislexia

Masi **no** usa una fuente especial por defecto. El estudio de Wery y Diliberto (*Annals of
Dyslexia*, 2017) comparó OpenDyslexic contra Arial y Times New Roman y no encontró mejora en
velocidad ni en precisión — y ningún participante prefirió esa fuente; un estudio de 2018 sobre
Dyslexie llegó a lo mismo. Cuatro décadas de investigación sitúan el origen de la dislexia en el
procesamiento fonológico, no en un déficit visual.

Lo que sí tiene evidencia es el **espaciado aumentado** (`letterSpacing = 0.12em`,
`lineHeight = 1.8em`), una sans-serif normal y la práctica fonológica sistemática. OpenDyslexic está
disponible como opción del usuario, porque la preferencia personal es legítima, pero nunca por
defecto.

---

## Poner en marcha

### Requisitos

- Android Studio con el SDK de Android 37 y JDK 17+ (vale el JBR que trae Android Studio).
- Un teléfono Android **12 o superior** (`minSdk 31`) con GPU. Probado en un Xiaomi Redmi Note 11
  Pro+ (MediaTek Dimensity 920, 6 GB de RAM).

```bash
adb shell getprop ro.build.version.sdk
```

### Compilar

```bash
./gradlew :app:installDebug
```

### El modelo

Son ~2,6 GB. Tres caminos, y la app acepta cualquiera:

**a) Descargarlo desde la app.** Botón "Descargar" en la pantalla de bienvenida. Se reanuda si se
corta.

**b) Copiarlo por USB.** El más rápido, y el que se usa para repartirlo sin datos móviles:

```bash
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/pe.masi/files/modelo/
```

**c) Elegirlo desde Archivos.** Botón "Ya lo tengo" — sirve si llegó por Bluetooth desde otro
teléfono.

Archivo oficial (no exportes el tuyo: `litert-torch export_hf` **no exporta el encoder de audio**):

```
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
```

---

## Verificar

### Tests unitarios

Cubren todo lo determinista, que es donde no hay excusa para fallar:

```bash
./gradlew :app:testDebugUnitTest
```

- `DetectorErroresTest` — alineación por distancia de edición, clasificación de errores,
  normalización (con la ñ intacta: "años" no puede convertirse en "anos").
- `PoliticaConservadoraTest` — **la mitad de este archivo comprueba que Masi NO corrige.** Variación
  vocálica andina, transcripción vacía, lectura incompleta, demasiadas discrepancias.
- `RepasoTest` — la escalera de intervalos, el reinicio al fallar, los bordes de fecha.
- `SilabasTest` — dígrafos, grupos consonánticos, diptongos e hiatos.
- `SegmentadorTest` — **el invariante de cobertura: unir los fragmentos devuelve el texto entero.**
  Sin él, el niño lee media página y cree que terminó. Además: nada de cortar por abreviaturas,
  decimales ni nombres propios compuestos.
- `EvaluadorPronunciacionTest` — que una transcripción poco fiable nunca se convierta en error.

### En el teléfono

Con el Redmi conectado y **el modo avión activado antes de abrir la app**:

1. Bienvenida: la animación cubre `initialize()` sin ANR. En `logcat`, `MasiMotor` confirma el
   backend. Si cae a CPU, revisar las `uses-native-library` del manifest — es un fallo silencioso.
2. Leer: fotografiar una página real del MINEDU → texto silabado en pantalla. Ajustar el recorte
   arrastrando una esquina: el marco debe seguir el dedo de forma continua.
3. Escuchar: las unidades deben ser **oraciones**, y recorriéndolas todas debe leerse la página
   entera. Leer con un error deliberado ("bero" por "perro") → se marca la palabra, el TUTOR
   responde en ≤ 2 frases y se escucha por TTS.
4. **Prueba de falso positivo:** leer la frase *bien* tres veces seguidas → no debe marcarse ningún
   error. Este criterio pesa más que el de detección.
5. Practicar: "Léela tú" → leerla bien sube la tarjeta de peldaño; leerla mal da pista y permite
   reintentarla ahí mismo. Cerrar y reabrir la app y comprobar que la palabra sigue ahí.
6. Memoria: abrir y cerrar 10 veces seguidas vigilando `adb shell dumpsys meminfo pe.masi` — no debe
   crecer. Mandar la app a segundo plano 40 s: la memoria nativa debe caer, porque el motor se
   suelta solo.
7. Modo demo (ajustes → engranaje): repetir el recorrido completo sin cargar el modelo.

---

## Atribución

### Pictogramas

Los pictogramas del banco (`app/src/main/assets/pictogramas/`) son **autoría de Sergio Palao**,
origen **[ARASAAC](https://arasaac.org)**, licencia **CC BY-NC-SA**, propiedad del **Gobierno de
Aragón**. Se regeneran con `python herramientas/descargar_pictogramas.py`.

El **NC obliga**: mientras Masi use este banco no puede comercializarse. Si algún día hiciera falta,
habría que sustituirlo por otro de licencia compatible — el `indice.json` aísla ese cambio, así que
solo habría que regenerar los archivos.

### Google AI Edge Gallery

Masi es un proyecto independiente, pero **no habría sido posible sin
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)** (Apache License 2.0), la app
de referencia de Google para LiteRT-LM en Android. De ella se estudiaron y adaptaron cinco piezas:

| Pieza de Masi | Origen en la Gallery |
|---|---|
| Configuración del `Engine` (GPU/GPU/CPU), chequeo de MTP, cierre de recursos | `ui/llmchat/LlmChatModelHelper.kt` |
| Grabación con `AudioRecord` a 16 kHz mono | `ui/common/chat/AudioRecorderPanel.kt` |
| Cabecera WAV para `Content.AudioBytes` | `ui/common/chat/ChatMessage.kt` |
| Captura con CameraX y reescalado del bitmap | `ui/common/chat/MessageInputText.kt` |
| Declaración de `libOpenCL.so` / `libvndksupport.so` | `AndroidManifest.xml` |

El orden de contenido por defecto (imagen → audio → texto) también viene de ahí; el model card de
Gemma 4 propone otro, y por eso es conmutable desde ajustes sin recompilar.

Modelo: **Gemma 4 E2B-it**, Apache License 2.0, de `litert-community`.

Masi se publica bajo Apache License 2.0.

---

## Lo que Masi no hace, y es deliberado

**Masi no diagnostica.** Detectar que un niño confunde b/d no es diagnosticar dislexia: hay una
docena de causas posibles, incluida la más común, que es que simplemente aún está aprendiendo. La
app usa el lenguaje de la práctica, no del déficit.

**Masi nunca etiqueta al niño.** Ni "nivel bajo", ni "tiene dificultades", ni un semáforo en rojo.
Todo el lenguaje visible es de progreso: "practicaste 12 palabras esta semana".

**Masi nunca ridiculiza el error.** Ni sonidos de fallo, ni cruces rojas, ni animaciones negativas.
La respuesta a un error empieza siempre reconociendo el intento.

**Masi no recoge datos.** Sin cuentas, sin publicidad, sin analítica, sin sincronización. El audio
se procesa en memoria y se descarta: nunca toca el disco.

**Masi complementa, no sustituye** ni al docente ni al especialista.

---

*"Porque aprender a leer no debería depender de dónde naciste."*
