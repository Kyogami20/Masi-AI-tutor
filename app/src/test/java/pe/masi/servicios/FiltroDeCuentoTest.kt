package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El último filtro antes de que un texto generado llegue a los ojos de un niño.
 *
 * Los casos de rechazo no son hipotéticos: son las formas conocidas en que un modelo de 2B se
 * desvía cuando está en mitad de un bucle de herramientas.
 */
class FiltroDeCuentoTest {

  private val objetivo = listOf("perro", "chacra")

  private val bueno =
    "Rosa vivía cerca de la chacra de su abuelo. Cada mañana salía con su perro a buscar leña. " +
      "Un día encontraron un nido con tres huevitos entre las piedras del camino. Rosa lo miró " +
      "con cuidado y no lo tocó. Su abuelo le dijo que había hecho muy bien. Volvieron a casa " +
      "contentos y comieron pan con queso fresco."

  @Test
  fun `acepta un cuento valido y le conserva el titulo`() {
    val r = FiltroDeCuento.revisar("El nido de Rosa", bueno, objetivo)
    assertTrue(r is RevisionCuento.Aceptado)
    assertEquals("El nido de Rosa", (r as RevisionCuento.Aceptado).titulo)
  }

  @Test
  fun `rechaza un texto demasiado corto`() {
    val r = FiltroDeCuento.revisar("Corto", "El perro corrió por la chacra.", objetivo)
    assertEquals(MotivoRechazo.DEMASIADO_CORTO, (r as RevisionCuento.Rechazado).motivo)
  }

  @Test
  fun `rechaza un texto que se pasa de largo`() {
    val larguisimo = (bueno + " ").repeat(4)
    val r = FiltroDeCuento.revisar("Largo", larguisimo, objetivo)
    assertEquals(MotivoRechazo.DEMASIADO_LARGO, (r as RevisionCuento.Rechazado).motivo)
  }

  @Test
  fun `rechaza un cuento sin ninguna de las palabras`() {
    // Si no aparece ninguna, el cuento no cumple la única función que tenía.
    val sinNinguna = bueno.replace("chacra", "casa").replace("perro", "gato")
    val r = FiltroDeCuento.revisar("Otro", sinNinguna, objetivo)
    assertEquals(MotivoRechazo.SIN_NINGUNA_PALABRA, (r as RevisionCuento.Rechazado).motivo)
  }

  @Test
  fun `acepta si falta una pero esta la otra`() {
    // Aquí NO se exige el 100 %: el umbral durante la generación ya lo gestiona el comprobador, y
    // tirar un cuento bueno por una palabra sería desperdiciar un minuto de generación.
    val r = FiltroDeCuento.revisar("Medio", bueno, listOf("perro", "montaña"))
    assertTrue(r is RevisionCuento.Aceptado)
  }

  @Test
  fun `rechaza cuando el modelo habla de si mismo en vez de narrar`() {
    val meta = "Aquí tienes el cuento que me pediste. " + bueno
    val r = FiltroDeCuento.revisar("Meta", meta, objetivo)
    assertEquals(MotivoRechazo.NO_ES_UN_CUENTO, (r as RevisionCuento.Rechazado).motivo)
  }

  @Test
  fun `rechaza cuando se cuela el andamiaje de las herramientas`() {
    val conJson = bueno + " ```json {\"titulo\": \"x\"}```"
    val r = FiltroDeCuento.revisar("Con json", conJson, objetivo)
    assertEquals(MotivoRechazo.NO_ES_UN_CUENTO, (r as RevisionCuento.Rechazado).motivo)
  }

  @Test
  fun `rechaza una llamada a herramienta mal formada que se coló como texto`() {
    // Caso real, visto en el teléfono: el modelo emitió `save_Story` en vez de `save_story`, el
    // runtime no lo reconoció como llamada y el texto crudo —delimitadores incluidos— acabó en la
    // pantalla del niño.
    val fugado =
      "<|tool_call>call:save_Story{story_<|\"|>Rosa fue a la sierra. Túpac fue al mercado. " +
        "Killa vio el río. Manuel fue a la chacra. Sisa fue a la escuela. Yaku fue feliz. " +
        "Ayer fue un día bonito. Todos jugaron mucho. Aprendieron cosas nuevas. Era soleado." +
        "<|\"|>,title:<|\"|>Día Feliz en Perú<|\"|>}"
    val r = FiltroDeCuento.revisar("", fugado, objetivo)
    assertEquals(MotivoRechazo.NO_ES_UN_CUENTO, (r as RevisionCuento.Rechazado).motivo)
  }

  @Test
  fun `quita el markdown que el modelo mete por su cuenta`() {
    // Visto en el teléfono: subraya con ** las palabras que le encargaste practicar. En la app no
    // hay quien interprete Markdown, así que el niño veía los asteriscos.
    val conMarcas = bueno.replace("perro", "**perro**").replace("chacra", "*chacra*")
    val r = FiltroDeCuento.revisar("El nido", conMarcas, objetivo) as RevisionCuento.Aceptado
    assertTrue("quedaron asteriscos: ${r.texto}", !r.texto.contains("*"))
    assertTrue("se perdió la palabra", r.texto.contains("perro"))
    assertTrue("se perdió la palabra", r.texto.contains("chacra"))
  }

  @Test
  fun `quita encabezados y subrayados`() {
    val conEncabezado = "## Un titulo" + System.lineSeparator() + bueno.replace("Rosa", "_Rosa_")
    val r = FiltroDeCuento.revisar("t", conEncabezado, objetivo)
    val aceptado = r as RevisionCuento.Aceptado
    assertTrue(!aceptado.texto.contains("#"))
    assertTrue(!aceptado.texto.contains("_"))
    assertTrue(aceptado.texto.contains("Rosa"))
  }

  @Test
  fun `separa el titulo de la primera linea`() {
    val respuesta = "El nido de Rosa" + System.lineSeparator() + System.lineSeparator() + bueno
    val crudo = FiltroDeCuento.partir(respuesta)
    assertEquals("El nido de Rosa", crudo.titulo)
    assertTrue("el título se quedó dentro del cuento", !crudo.texto.startsWith("El nido"))
    assertTrue("se perdió el cuento", crudo.texto.startsWith("Rosa vivía"))
  }

  @Test
  fun `no confunde la primera frase del cuento con un titulo`() {
    // Sin línea de título: la primera línea acaba en punto, que es lo que delata a una frase.
    val crudo = FiltroDeCuento.partir(bueno)
    assertEquals("", crudo.titulo)
    assertTrue("se comió el principio", crudo.texto.startsWith("Rosa vivía"))
  }

  @Test
  fun `un titulo largo no se recorta, se descarta`() {
    // REGRESIÓN. Recortar a cinco palabras produjo "La pelota perdida en el" y "Mateo y el sol de":
    // el modelo escribía bien y el corte lo destrozaba. Ahora, o vale entero, o decide el respaldo.
    val larguisimo = "Un titulo francamente demasiado largo para caber en una carta de la biblioteca"
    val crudo = FiltroDeCuento.partir(larguisimo + System.lineSeparator() + bueno)
    assertEquals("", crudo.titulo)
  }

  @Test
  fun `un titulo de cinco palabras se conserva entero`() {
    val crudo = FiltroDeCuento.partir("La pelota perdida en el jardin" + System.lineSeparator() + bueno)
    assertEquals("La pelota perdida en el jardin", crudo.titulo)
  }

  @Test
  fun `un cuento sin titulo no se queda sin nombre en la biblioteca`() {
    val r = FiltroDeCuento.revisar("", bueno, objetivo) as RevisionCuento.Aceptado
    assertTrue("debía inventarse un título: '${r.titulo}'", r.titulo.length >= 3)
  }

  @Test
  fun `el titulo de respaldo no copia el principio del cuento`() {
    // REGRESIÓN. El respaldo copiaba las cuatro primeras palabras más puntos suspensivos, y como el
    // CUENTISTA había dejado de dar títulos, TODOS los cuentos se llamaban así: "Rosa vivía cerca…".
    // Un respaldo que funciona a tiempo completo deja de ser un respaldo.
    val r = FiltroDeCuento.revisar("", bueno, objetivo) as RevisionCuento.Aceptado
    assertTrue("sigue copiando el principio: '${r.titulo}'", !bueno.startsWith(r.titulo.take(12)))
    assertTrue("quedaron puntos suspensivos", !r.titulo.contains("…"))
    // Y aun siendo respaldo, dice algo: saca el nombre del protagonista.
    assertTrue("no nombró a Rosa: '${r.titulo}'", r.titulo.contains("Rosa"))
  }

  @Test
  fun `un titulo del modelo se respeta tal cual`() {
    val r = FiltroDeCuento.revisar("El nido de Rosa", bueno, objetivo) as RevisionCuento.Aceptado
    assertEquals("El nido de Rosa", r.titulo)
  }

  @Test
  fun `limpia la decoracion del titulo`() {
    val r = FiltroDeCuento.revisar("**\"El nido\"**", bueno, objetivo) as RevisionCuento.Aceptado
    assertEquals("El nido", r.titulo)
  }

  @Test
  fun `un texto vacio nunca pasa`() {
    val r = FiltroDeCuento.revisar("", "   ", objetivo)
    assertEquals(MotivoRechazo.DEMASIADO_CORTO, (r as RevisionCuento.Rechazado).motivo)
  }
}
