#!/usr/bin/env python3
"""Descarga el banco de pictogramas de ARASAAC y lo deja listo para empaquetar en el APK.

Se versiona en el repo para que el banco sea REPRODUCIBLE: cualquiera puede regenerarlo o
ampliarlo sin depender de que alguien lo hiciera una vez y subiera los binarios a ciegas.

    python herramientas/descargar_pictogramas.py

Deja en app/src/main/assets/pictogramas/ un WebP por palabra encontrada y un indice.json.

Por qué se empaqueta en el APK y no se descarga desde la app, como sí se hace con Gemma: el
modelo son 2,6 GB y no cabe, así que su descarga es un arranque inevitable. Esto son ~3 MB y sí
cabe. Convertirlo en una descarga opcional significaría que el niño cuyo padre no la completó se
queda sin pictogramas y sin saber por qué; y además los archivos de `getExternalFilesDir`
desaparecen al desinstalar la app, cosa que ya nos costó un modelo entero.

LICENCIA de los pictogramas descargados: CC BY-NC-SA.
    Autor: Sergio Palao. Origen: ARASAAC (https://arasaac.org). Propiedad: Gobierno de Aragón.
La atribución es obligatoria y está en la pantalla de Ajustes y en el README. El NC significa que
Masi no puede comercializarse mientras use este banco.
"""

from __future__ import annotations

import io
import json
import os
import time
import urllib.parse
import urllib.request

from PIL import Image

API = "https://api.arasaac.org/v1/pictograms"
DESTINO = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "pictogramas")

# 256 px basta: en la tarjeta se ve a ~120 dp. WebP con calidad 80 sobre dibujo de línea plana da
# unos 3-4 KB por pictograma, frente a los 11 KB del PNG original.
LADO = 256
CALIDAD = 80

# Cortesía con una API pública y gratuita que no nos ha pedido nada.
ESPERA_S = 0.15

CON_TILDE = "áàäâéèëêíìïîóòöôúùûü"
SIN_TILDE = "aaaaeeeeiiiioooouuuu"


def normalizar(texto: str) -> str:
    """La MISMA normalización que `DetectorErrores.normalizar` en Kotlin.

    Minúsculas y sin tildes, pero **la ñ se conserva**: "año" y "ano" son palabras distintas. Si
    esto se desalinea del lado Kotlin, las búsquedas fallan en silencio.
    """
    tabla = str.maketrans(CON_TILDE, SIN_TILDE)
    return texto.lower().translate(tabla).strip()


# Vocabulario de partida.
#
# No pretende ser exhaustivo ni podría serlo: son las cosas concretas que aparecen en un libro de
# primaria peruano y que un niño de 7 años puede tropezar al leer. Las palabras raras NO van aquí a
# propósito — para "estableció" no hay pictograma y no lo habrá nunca; ahí es donde el modelo tiene
# que razonar que trata de "construir" y buscar eso. Un banco pequeño de conceptos comunes más un
# modelo que razona cubre más que un banco enorme con búsqueda exacta.
PALABRAS = [
    # Casa y familia
    "casa", "puerta", "ventana", "cama", "mesa", "silla", "cocina", "plato", "vaso", "cuchara",
    "mamá", "papá", "hermano", "hermana", "abuelo", "abuela", "bebé", "niño", "niña", "familia",
    "amigo", "vecino", "ropa", "zapato", "sombrero", "chompa", "cama", "manta", "jabón", "peine",
    # Escuela
    "escuela", "libro", "cuaderno", "lápiz", "borrador", "tijeras", "mochila", "pizarra", "maestra",
    "leer", "escribir", "contar", "dibujar", "pintar", "estudiar", "aprender", "pensar", "preguntar",
    "letra", "palabra", "número", "papel", "regla", "colegio", "tarea", "clase",
    # Animales
    "perro", "gato", "gallina", "pollo", "pato", "vaca", "toro", "caballo", "burro", "chancho",
    "oveja", "cabra", "llama", "alpaca", "cuy", "conejo", "ratón", "pájaro", "loro", "cóndor",
    "pez", "rana", "araña", "hormiga", "abeja", "mariposa", "mosca", "gusano", "serpiente", "tortuga",
    "mono", "oso", "zorro", "venado", "búho", "águila", "pulpo", "ballena", "caracol", "murciélago",
    # Comida
    "pan", "leche", "queso", "huevo", "arroz", "papa", "camote", "maíz", "choclo", "quinua",
    "frijol", "sopa", "carne", "pescado", "pollo", "fruta", "plátano", "manzana", "naranja", "uva",
    "piña", "sandía", "limón", "palta", "tomate", "cebolla", "zanahoria", "lechuga", "azúcar", "sal",
    "agua", "jugo", "café", "chocolate", "helado", "torta", "galleta", "caramelo", "miel", "mantequilla",
    # Naturaleza y lugares
    "sol", "luna", "estrella", "nube", "lluvia", "viento", "nieve", "cielo", "fuego", "tierra",
    "montaña", "cerro", "río", "lago", "mar", "playa", "isla", "bosque", "selva", "desierto",
    "árbol", "hoja", "flor", "planta", "semilla", "raíz", "pasto", "piedra", "arena", "camino",
    "campo", "huerto", "jardín", "granja", "pueblo", "ciudad", "calle", "plaza", "iglesia", "puente",
    "mercado", "tienda", "hospital", "farmacia", "banco", "parque", "museo", "biblioteca", "cine", "restaurante",
    # Cuerpo
    "cabeza", "pelo", "ojo", "oreja", "nariz", "boca", "diente", "lengua", "cuello", "hombro",
    "brazo", "mano", "dedo", "pierna", "pie", "rodilla", "espalda", "barriga", "corazón", "cara",
    # Verbos frecuentes
    "comer", "beber", "dormir", "correr", "caminar", "saltar", "jugar", "cantar", "bailar", "reír",
    "llorar", "hablar", "escuchar", "mirar", "tocar", "abrir", "cerrar", "subir", "bajar", "entrar",
    "salir", "venir", "llevar", "traer", "dar", "tomar", "buscar", "encontrar", "perder", "guardar",
    "lavar", "limpiar", "cocinar", "comprar", "vender", "trabajar", "ayudar", "cuidar", "curar", "romper",
    "construir", "sembrar", "cosechar", "regar", "cortar", "pegar", "empujar", "jalar", "lanzar", "atrapar",
    "esperar", "llegar", "volver", "viajar", "nadar", "volar", "montar", "conducir", "descansar", "despertar",
    # Sentimientos y cualidades
    "feliz", "triste", "enojado", "asustado", "cansado", "sorprendido", "tranquilo", "nervioso",
    "grande", "pequeño", "alto", "bajo", "largo", "corto", "gordo", "flaco", "nuevo", "viejo",
    "limpio", "sucio", "caliente", "frío", "lleno", "vacío", "rápido", "lento", "fuerte", "débil",
    "bonito", "feo", "bueno", "malo", "fácil", "difícil", "duro", "blando", "dulce", "salado",
    # Colores y formas
    "rojo", "azul", "verde", "amarillo", "negro", "blanco", "naranja", "morado", "rosado", "marrón",
    "círculo", "cuadrado", "triángulo", "estrella", "corazón",
    # Objetos y transporte
    "carro", "camión", "bus", "tren", "avión", "barco", "bicicleta", "moto", "llanta", "llave",
    "reloj", "teléfono", "radio", "televisor", "computadora", "cámara", "pelota", "muñeca", "globo", "juguete",
    "dinero", "moneda", "bolsa", "caja", "botella", "olla", "cuchillo", "tenedor", "escoba", "balde",
    "martillo", "clavo", "cuerda", "canasta", "sombrilla", "espejo", "vela", "foco", "puerta", "escalera",
    # Tiempo
    "día", "noche", "mañana", "tarde", "hoy", "ayer", "semana", "mes", "año", "hora",
    "lunes", "domingo", "verano", "invierno", "cumpleaños", "fiesta",

    # --- Conceptos puente ---
    #
    # Esta sección NO está aquí por vocabulario escolar: está por lo que hace el modelo. Cuando una
    # palabra no tiene pictograma, el ENRIQUECEDOR razona un concepto emparentado y vuelve a buscar.
    # Estas son las que pidió de verdad, sacadas del log del teléfono:
    #
    #   'pesame'  → intentos=[pesame ✗, tristeza ✗, luto ✗, sentir ✗]
    #   'sentido' → intentos=[sentido ✗, significado ✗, propósito ✗]
    #
    # El modelo hacía su parte y el banco no tenía dónde aterrizar. Son sustantivos abstractos y
    # verbos generales: los destinos naturales de ese salto conceptual.
    "sentir", "pensar", "querer", "recordar", "olvidar", "saber", "entender", "explicar",
    "significado", "idea", "palabra", "pregunta", "respuesta", "problema", "solución",
    "tristeza", "alegría", "miedo", "enojo", "sorpresa", "amor", "cariño", "abrazo", "beso",
    "muerte", "vida", "nacer", "morir", "enfermo", "sano", "dolor", "consuelo", "ayuda",
    "principio", "final", "cambio", "orden", "grupo", "parte", "todo", "nada",
    "verdad", "mentira", "secreto", "sueño", "juego", "trabajo", "descanso", "viaje",
    "fuerza", "peligro", "cuidado", "silencio", "ruido", "luz", "sombra", "color",
]


def buscar(palabra: str) -> int | None:
    """Devuelve el id del mejor pictograma, o None.

    Dos endpoints porque hacen falta los dos: `bestsearch` es preciso pero estricto y devuelve vacío
    con demasiada frecuencia; `search` es amplio y su primer resultado suele valer. **La palabra hay
    que codificarla**: sin `quote`, "montaña" devuelve cero resultados y con él devuelve cuarenta y
    cinco. Ese detalle costó un rato de despiste.
    """
    codificada = urllib.parse.quote(palabra)
    for endpoint in ("bestsearch", "search"):
        try:
            with urllib.request.urlopen(f"{API}/es/{endpoint}/{codificada}", timeout=20) as r:
                datos = json.load(r)
            if isinstance(datos, list) and datos:
                return datos[0]["_id"]
        except Exception:
            pass
        time.sleep(ESPERA_S)
    return None


def descargar(id_picto: int) -> bytes | None:
    try:
        with urllib.request.urlopen(f"{API}/{id_picto}?resolution=500", timeout=30) as r:
            return r.read()
    except Exception:
        return None


def main() -> None:
    os.makedirs(DESTINO, exist_ok=True)
    indice: dict[str, str] = {}
    sin_pictograma: list[str] = []
    vistas: set[str] = set()

    palabras = [p for p in PALABRAS if not (normalizar(p) in vistas or vistas.add(normalizar(p)))]
    print(f"{len(palabras)} palabras únicas")

    for i, palabra in enumerate(palabras, 1):
        clave = normalizar(palabra)
        # Ya descargada: se reutiliza. Así ampliar el banco cuesta solo las palabras nuevas, en vez
        # de volver a pedirle a ARASAAC las trescientas de siempre.
        ruta_existente = os.path.join(DESTINO, f"{clave}.webp")
        if os.path.exists(ruta_existente):
            indice[clave] = f"{clave}.webp"
            continue

        id_picto = buscar(palabra)
        if id_picto is None:
            sin_pictograma.append(palabra)
            print(f"  [{i}/{len(palabras)}] {palabra}: sin pictograma")
            continue

        crudo = descargar(id_picto)
        if crudo is None:
            sin_pictograma.append(palabra)
            continue

        # A WebP y a 256 px: el PNG de 500 px pesa 11 KB y así baja a 3-4 KB. Se conserva el canal
        # alfa, que en un pictograma con fondo transparente no es opcional.
        imagen = Image.open(io.BytesIO(crudo)).convert("RGBA")
        imagen.thumbnail((LADO, LADO), Image.LANCZOS)
        nombre = f"{clave}.webp"
        imagen.save(os.path.join(DESTINO, nombre), "WEBP", quality=CALIDAD, method=6)
        indice[clave] = nombre
        print(f"  [{i}/{len(palabras)}] {palabra} -> {nombre}")
        time.sleep(ESPERA_S)

    with open(os.path.join(DESTINO, "indice.json"), "w", encoding="utf-8") as f:
        json.dump(
            {
                "version": 1,
                "licencia": (
                    "Pictogramas: Sergio Palao. Origen: ARASAAC (https://arasaac.org). "
                    "Licencia: CC BY-NC-SA. Propiedad: Gobierno de Aragón."
                ),
                "pictogramas": dict(sorted(indice.items())),
            },
            f,
            ensure_ascii=False,
            indent=2,
        )

    total = sum(
        os.path.getsize(os.path.join(DESTINO, n)) for n in os.listdir(DESTINO) if n.endswith(".webp")
    )
    print(f"\n{len(indice)} pictogramas, {total / 1_048_576:.1f} MB")
    if sin_pictograma:
        print(f"sin pictograma ({len(sin_pictograma)}): {', '.join(sin_pictograma)}")


if __name__ == "__main__":
    main()
