# Masi

**Compañero de lectura 100 % offline, con Gemma 4 E2B ejecutándose dentro del teléfono, para niños
con dificultades de lectoescritura en el Perú.**

*Masi* significa "compañero, amigo" en quechua.

[![Licencia: Apache 2.0](https://img.shields.io/badge/Licencia-Apache%202.0-blue.svg)](LICENSE)
![Plataforma](https://img.shields.io/badge/Plataforma-Android%2012%2B-3DDC84)
![Offline](https://img.shields.io/badge/Funciona-100%25%20offline-success)
![Modelo](https://img.shields.io/badge/IA-Gemma%204%20E2B%20on--device-orange)

---

## Índice

1. [El problema](#el-problema)
2. [La solución: Masi](#la-solución-masi)
3. [Funcionalidades](#funcionalidades)
4. [Demo](#demo)
5. [Cómo funciona por dentro](#cómo-funciona-por-dentro)
6. [Por qué offline importa aquí](#por-qué-offline-importa-aquí)
7. [Stack tecnológico](#stack-tecnológico)
8. [Puesta en marcha](#puesta-en-marcha)
9. [Verificar que funciona](#verificar-que-funciona)
10. [Estructura del proyecto](#estructura-del-proyecto)
11. [Limitaciones actuales](#limitaciones-actuales)
12. [Lo que Masi no hace, y es deliberado](#lo-que-masi-no-hace-y-es-deliberado)
13. [Documentación técnica adicional](#documentación-técnica-adicional)
14. [Atribución](#atribución)
15. [Licencia](#licencia)

---

## El problema

Aprender a leer es más difícil para un niño con dislexia u otras dificultades de lectoescritura, y
lo es todavía más si además no tiene acceso a un especialista, a materiales adaptados o siquiera a
conexión a Internet. En muchas zonas rurales del Perú —Huancavelica es el caso que este proyecto tuvo
en mente desde el primer commit— esa combinación es la norma, no la excepción: sin señal, sin datos
móviles y sin un logopeda a la vuelta de la esquina, la práctica de lectura en voz alta que de verdad
ayuda a un niño con dificultades simplemente no ocurre en casa.

Las soluciones existentes de lectura asistida por IA casi siempre asumen una conexión estable a un
servidor en la nube. Eso las descarta de entrada para el contexto que Masi intenta atender.

## La solución: Masi

Masi es una app Android que acompaña a un niño mientras practica lectura en voz alta, **con el modelo
de inteligencia artificial corriendo dentro del propio teléfono**, sin servidor y sin red. Hace tres
cosas, y solo tres:

1. **Ve.** El niño fotografía la página de su libro. El modelo extrae el texto y lo devuelve
   adaptado: espaciado amplio, sílabas separadas.
2. **Escucha.** El niño lee en voz alta, oración a oración hasta terminar la página. Masi compara lo
   que dijo contra lo que estaba escrito y detecta qué palabras falló.
3. **Acompaña.** Explica el error con amabilidad y una pista concreta, y guarda esa palabra para
   practicarla otro día —leyéndola de nuevo en voz alta, no marcando una casilla en un formulario.

**No hay nube, no hay servidor, no hay red.** El modelo (Gemma 4 E2B) vive en el almacenamiento del
teléfono y la app funciona íntegramente en modo avión. La voz de un niño no sale del dispositivo
porque no hay ningún sitio al que pudiera salir.

## Funcionalidades

| Pantalla | Qué hace |
|---|---|
| **Leer** | Fotografía una página de un libro escolar y la convierte en texto accesible (espaciado amplio, sílabas separadas), con recorte manual para enfocar solo el texto. |
| **Escuchar** | El niño lee en voz alta, frase por frase; Masi detecta las palabras que salieron distintas y da una pista amable para cada una, sin nunca marcar un error dudoso. |
| **Tarjetas** | Las palabras falladas quedan guardadas en un sistema de repetición espaciada. Practicar es volver a leerlas en voz alta —el nivel solo sube si el micrófono confirma que se pronunció bien, no por un botón de "ya me la sé". Cada tarjeta puede llevar, además, una definición sencilla, un ejemplo de uso y un dibujo (pictograma) que la IA le busca en segundo plano. |
| **Cuentos** | Con las palabras que peor le van al niño, la IA escribe un cuento corto original que las usa de forma natural. Se guarda en una biblioteca y se puede releer sin necesidad de tener el modelo cargado. |
| **Ajustes** | Modo demo (para presentar la app sin cargar el modelo), tipografía OpenDyslexic opcional, preferencia de aceleración GPU/CPU, e informe del teléfono para depurar sin recoger datos del niño. |

## Demo

> _Capturas de pantalla y video de demo: pendientes de agregar._
>
> Sugerencia de estructura una vez que estén listas:
> ```
> docs/
>   screenshots/
>     leer.png
>     escuchar.png
>     tarjetas.png
>     cuentos.png
> ```
> y enlazarlas aquí como `![Pantalla de Leer](docs/screenshots/leer.png)`.

## Cómo funciona por dentro

Masi corre en **un solo proceso Android**: no hay "frontend" hablándole a un "backend" por HTTP,
todo —interfaz, lógica de negocio y el modelo de IA— vive en la misma app.

```
┌─────────────────────────────────────────────────────────────┐
│  UN SOLO PROCESO — la app de Masi en el teléfono            │
│                                                             │
│  ui/            Jetpack Compose · cámara · micro · voz      │
│         │                                                   │
│  servicios/     LectorService     imagen → texto accesible  │
│                 EscuchaService    audio  → transcripción    │
│                 TutorService      error  → pista amable     │
│                 CuentistaService  palabras → cuento          │
│                 EnriquecedorService palabra → definición +  │
│                                      ejemplo + dibujo        │
│                 DetectorErrores + PoliticaConservadora      │
│                 (comparación de lecturas, 100 % código,     │
│                  nunca decidida por el modelo)               │
│         │                                                   │
│  motor/         MotorMasi → LiteRT-LM (Engine·Conversation) │
│                 GPU (texto/visión) + CPU (audio),            │
│                 con degradación automática si un teléfono    │
│                 no soporta GPU                                │
│         │                                                   │
│  datos/         Room — tarjetas, cuentos y progreso,          │
│                 todo en el propio aparato                     │
└─────────────────────────────────────────────────────────────┘
                              ▲ lee (no ejecuta remotamente)
                  ┌───────────┴────────────┐
                  │  gemma-4-E2B-it        │
                  │  .litertlm  (~2,6 GB)  │
                  └────────────────────────┘
```

Un único modelo cargado en RAM (Gemma 4 E2B-it) se usa con **cinco personalidades distintas**, cada
una con su propio system prompt y su propia temperatura, según lo que tenga que hacer en cada
momento:

| Agente | Hace | Temperatura | Por qué |
|---|---|---|---|
| LECTOR | Foto → texto transcrito | 0.2 | Transcribir es determinista. Creatividad = alucinación |
| ESCUCHA | Audio → transcripción fonética literal | 0.1 | Aún más estricto: aquí la creatividad es el enemigo |
| TUTOR | Error → pista amable | 0.7 | Aquí sí queremos variedad, que no repita la misma frase |
| CUENTISTA | Palabras difíciles → cuento corto | 0.9 | Dos cuentos con las mismas palabras deben salir distintos |
| ENRIQUECEDOR | Palabra → definición + dibujo | 0.4 | Sigue una secuencia de herramientas, no prosa libre |

Solo se mantiene **una conversación activa a la vez**: cambiar de agente la recrea (barato — no
recarga el modelo, solo reinicia su memoria de contexto), porque en un teléfono de 6 GB mantener
varias conversaciones vivas a la vez compite por una memoria que no sobra.

### Lo difícil: el audio

La intuición dice que basta con transcribir lo que el niño dijo y compararlo con lo que debía decir.
**No funciona por defecto**, y la razón es interesante: un reconocedor de voz está entrenado para
producir la transcripción *más probable*, así que cuando el niño lee "El bero corre" el modelo oye
algo ambiguo en un contexto donde "perro" es abrumadoramente más probable — y escribe "perro".
**Corrige exactamente el error que hay que detectar.**

Cuatro capas de mitigación, y dónde vive cada una:

| Capa | Qué hace | Dónde |
|---|---|---|
| 1 | Prompt que fuerza transcripción literal, con el texto esperado en la primera línea | [`Prompts.kt`](app/src/main/java/pe/masi/motor/Prompts.kt) |
| 2 | Fragmentos de 10 palabras como mucho, y clips de 12 s (3 s si es una palabra suelta), nunca 30 | [`Segmentador.kt`](app/src/main/java/pe/masi/servicios/Segmentador.kt) · [`GrabadorAudio.kt`](app/src/main/java/pe/masi/media/GrabadorAudio.kt) |
| 3 | Comparación en código determinista, nunca en el modelo | [`DetectorErrores.kt`](app/src/main/java/pe/masi/servicios/DetectorErrores.kt) |
| 4 | Umbral conservador: ante la duda, no se marca error | [`PoliticaConservadora.kt`](app/src/main/java/pe/masi/servicios/PoliticaConservadora.kt) |

La capa 4 es el corazón ético del proyecto. Marcar como fallo la lectura correcta de un niño le
enseña que es malo leyendo, y ese daño dura años. Además, el español andino tiene realizaciones
vocálicas distintas del limeño (por contacto con el quechua), así que un modelo puede marcar como
error de lectura lo que es simplemente el acento del niño. Por eso: **es preferible dejar pasar un
error real a inventar uno falso.**

### Adaptación automática al teléfono

Antes de cargar el modelo, Masi mide la memoria disponible real del dispositivo y ajusta el tamaño de
contexto, el tamaño de la foto y si conviene precalentar la visión. Si la GPU falla al procesar
imágenes (le pasó a un teléfono real del equipo), el motor **baja de nivel solo** —de GPU completa, a
GPU solo para texto, a CPU total— y recuerda esa decisión para no repetir el fallo en cada arranque.

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

## Por qué offline importa aquí

No es una limitación técnica que se tolera: es el requisito de partida. Un niño en una zona rural sin
señal no puede depender de una app que necesite nube para funcionar, y la voz de un menor no debería
salir del dispositivo si se puede evitar. Por eso:

- El modelo se descarga **una sola vez** (o llega por USB/Bluetooth, sin datos móviles) y a partir de
  ahí la app funciona en modo avión.
- El audio del niño se procesa en memoria y se descarta: **nunca toca el disco**.
- No hay cuentas, ni analítica, ni publicidad, ni sincronización con ningún servidor.

## Stack tecnológico

| Tecnología | Para qué |
|---|---|
| Kotlin + Jetpack Compose | Lenguaje y UI declarativa de toda la app |
| **LiteRT-LM** (`litertlm-android` 0.11.0) | Motor de inferencia on-device de Google AI Edge: carga el modelo, expone `Engine`/`Conversation`, function calling nativo, backend GPU/CPU |
| **Gemma 4 E2B-it** | Modelo de lenguaje multimodal (texto + imagen + audio) que hace de LECTOR, ESCUCHA, TUTOR, CUENTISTA y ENRIQUECEDOR |
| CameraX | Captura de la foto de la página del libro |
| `AudioRecord` (Android SDK) | Grabación de audio crudo PCM a 16 kHz, sin librerías externas |
| `TextToSpeech` (Android SDK) | La voz de Masi: toda pantalla se locuta, porque el usuario todavía no sabe leer bien |
| Room | Base de datos local: tarjetas de palabras y cuentos generados |
| DataStore Preferences | Ajustes del adulto |
| kotlinx.serialization | Parseo tolerante del JSON que devuelve el modelo |

No hay backend HTTP propio, ni OCR de terceros, ni servicio de voz en la nube, ni SDK de analítica.
La única llamada de red de toda la app es la descarga inicial y opcional del archivo del modelo.

## Puesta en marcha

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

### Modo demo, sin modelo

Ajustes → pulsación larga sobre el engranaje activa un **modo demo** con respuestas precocinadas:
recorre las cuatro pantallas —Leer, Escuchar, Tarjetas, Cuentos— sin cargar el modelo. Pensado
explícitamente para presentar la app en un jurado sin depender de la descarga de 2,6 GB.

## Verificar que funciona

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
  Sin él, el niño lee media página y cree que terminó.
- `EvaluadorPronunciacionTest` — que una transcripción poco fiable nunca se convierta en error.
- `FiltroDeCuentoTest`, `ComprobadorDePalabrasTest` — que un cuento generado sea presentable y que
  incluya de verdad las palabras que el niño necesita practicar.
- `EscaleraDeBackendTest` — cuándo el motor debe degradar de GPU a CPU y cuándo no.
- `LimpiadorDeEcoTest`, `MemoriaDeDudasTest` — casos reales medidos en teléfono donde el modelo se
  repetía a sí mismo o dudaba de una sustitución.

### En el teléfono

Con el modo avión activado antes de abrir la app:

1. **Bienvenida**: la animación cubre `initialize()` sin ANR. En `logcat`, `MasiMotor` confirma el
   backend con el que arrancó.
2. **Leer**: fotografiar una página real de un libro escolar → texto silabado en pantalla.
3. **Escuchar**: las unidades deben ser **oraciones**, y recorriéndolas todas debe leerse la página
   entera. Leer con un error deliberado ("bero" por "perro") → se marca la palabra, el TUTOR
   responde en ≤ 2 frases y se escucha por voz.
4. **Prueba de falso positivo**: leer la frase *bien* tres veces seguidas → no debe marcarse ningún
   error. Este criterio pesa más que el de detección.
5. **Tarjetas**: "Léela tú" → leerla bien sube la tarjeta de peldaño; leerla mal da pista y permite
   reintentarla ahí mismo.
6. **Cuentos**: generar uno nuevo debe incluir al menos una de las palabras que peor le van al niño,
   y debe poder releerse después con el modelo apagado.
7. **Modo demo** (ajustes → engranaje): repetir el recorrido completo sin cargar el modelo.

## Estructura del proyecto

```
app/src/main/java/pe/masi/
├── ui/            Pantallas Compose + MasiViewModel + navegación
├── servicios/     Lógica de negocio: comparación de lecturas, repetición espaciada,
│                  segmentación, sílabas, generación de cuentos, enriquecimiento
├── motor/         Envoltorio de LiteRT-LM: Engine, Conversation, prompts, backend GPU/CPU
├── media/         Cámara, grabación de audio, texto a voz
├── datos/         Room (tarjetas, cuentos) y DataStore (preferencias)
├── diagnostico/   Caja negra local para detectar cierres sin telemetría en la nube
└── demo/          Modo demo con respuestas precocinadas
```

## Limitaciones actuales

Dicho con la misma honestidad con la que está documentado el código:

- El modelo pesa ~2,6 GB y va mejor con GPU y 4–6 GB de RAM; en teléfonos muy limitados sigue
  funcionando, pero más lento (backend en CPU).
- La primera carga del modelo y la primera foto de cada sesión son más lentas (10–20 s extra) porque
  los encoders de visión y audio se cargan bajo demanda.
- La política conservadora de detección de errores prioriza **no inventar errores falsos** por
  encima de detectar todos los errores reales — es un trade-off deliberado, no un descuido, pero
  significa que algunos errores reales no se marcan.
- Probado a fondo en un dispositivo de referencia (Redmi Note 11 Pro+); el comportamiento en gama muy
  baja depende de la escalera de degradación automática GPU→CPU.

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

## Documentación técnica adicional

Este README está pensado para dar una visión completa pero no exhaustiva. Para un desglose línea a
línea de cada pieza —incluyendo el flujo de datos completo, decisiones de ingeniería con su
justificación medida en teléfono real, y una batería de preguntas técnicas de jurado con
respuestas ancladas al código— ver [`INFORME_TECNICO_HACKATON.md`](INFORME_TECNICO_HACKATON.md).

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

## Licencia

Masi se publica bajo **Apache License 2.0** (ver [`LICENSE`](LICENSE)).

---

*"Porque aprender a leer no debería depender de dónde naciste."*
