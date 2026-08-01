# El motor nativo de LiteRT-LM se resuelve por JNI: no renombrar sus clases.
-keep class com.google.ai.edge.litertlm.** { *; }

# Los DTOs que se parsean desde el JSON del modelo. kotlinx.serialization ya trae sus propias
# reglas, pero un fallo aquí solo aparecería en la build de release —justo la que se lleva al
# evento— así que se dejan explícitas.
-keep,includedescriptorclasses class pe.masi.servicios.PaginaLeida { *; }
-keep,includedescriptorclasses class pe.masi.servicios.TranscripcionLiteral { *; }
-keepclassmembers class pe.masi.** {
    *** Companion;
}
-keepclasseswithmembers class pe.masi.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Las entidades de Room se instancian desde código generado.
-keep class pe.masi.datos.Tarjeta { *; }

# Silenciar el ruido de R8 al leer metadatos de Kotlin más nuevos que su propia versión.
-dontwarn kotlin.**
