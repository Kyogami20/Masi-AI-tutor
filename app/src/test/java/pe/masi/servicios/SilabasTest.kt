package pe.masi.servicios

import org.junit.Assert.assertEquals
import org.junit.Test

class SilabasTest {

  @Test
  fun `una consonante entre vocales pasa a la silaba siguiente`() {
    assertEquals("ca-sa", Silabas.separar("casa"))
    assertEquals("pe-lo-ta", Silabas.separar("pelota"))
  }

  @Test
  fun `los digrafos no se parten`() {
    assertEquals("pe-rro", Silabas.separar("perro"))
    assertEquals("ca-lle", Silabas.separar("calle"))
    assertEquals("le-che", Silabas.separar("leche"))
  }

  @Test
  fun `los grupos con l y r no se parten`() {
    assertEquals("pla-to", Silabas.separar("plato"))
    assertEquals("li-bro", Silabas.separar("libro"))
    assertEquals("ma-dre", Silabas.separar("madre"))
    assertEquals("re-gla", Silabas.separar("regla"))
  }

  @Test
  fun `dos consonantes que no forman grupo se reparten`() {
    assertEquals("cam-po", Silabas.separar("campo"))
    assertEquals("ár-bol", Silabas.separar("árbol"))
    assertEquals("mur-cié-la-go", Silabas.separar("murciélago"))
  }

  @Test
  fun `tres consonantes seguidas`() {
    assertEquals("trans-por-te", Silabas.separar("transporte"))
    assertEquals("ins-tru-men-to", Silabas.separar("instrumento"))
  }

  @Test
  fun `los diptongos se mantienen juntos`() {
    assertEquals("ai-re", Silabas.separar("aire"))
    assertEquals("cui-da-do", Silabas.separar("cuidado"))
    assertEquals("a-gua", Silabas.separar("agua"))
  }

  @Test
  fun `dos vocales fuertes hacen hiato`() {
    assertEquals("le-er", Silabas.separar("leer"))
    assertEquals("ca-os", Silabas.separar("caos"))
    assertEquals("po-e-ta", Silabas.separar("poeta"))
  }

  @Test
  fun `la tilde en vocal debil rompe el diptongo`() {
    assertEquals("dí-a", Silabas.separar("día"))
    assertEquals("ba-úl", Silabas.separar("baúl"))
    assertEquals("Ma-rí-a", Silabas.separar("María"))
  }

  @Test
  fun `la u de que y gue no forma silaba aparte`() {
    assertEquals("que-so", Silabas.separar("queso"))
    assertEquals("gui-ta-rra", Silabas.separar("guitarra"))
    assertEquals("pin-güi-no", Silabas.separar("pingüino"))
  }

  @Test
  fun `la h no rompe nada`() {
    assertEquals("a-ho-ra", Silabas.separar("ahora"))
    assertEquals("pro-hi-bir", Silabas.separar("prohibir"))
  }

  @Test
  fun `los monosilabos se devuelven enteros`() {
    assertEquals("pan", Silabas.separar("pan"))
    assertEquals("sol", Silabas.separar("sol"))
    assertEquals("tres", Silabas.separar("tres"))
  }

  @Test
  fun `casos degenerados no revientan`() {
    assertEquals("", Silabas.separar(""))
    assertEquals("", Silabas.separar("   "))
    assertEquals("psst", Silabas.separar("psst"))
  }

  @Test
  fun `separarTexto respeta la puntuacion y los espacios`() {
    assertEquals("El pe-rro co-rre.", Silabas.separarTexto("El perro corre."))
  }
}
