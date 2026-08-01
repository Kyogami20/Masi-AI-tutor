# El motor nativo de LiteRT-LM se resuelve por JNI: no renombrar sus clases.
-keep class com.google.ai.edge.litertlm.** { *; }

# --- Function calling: las herramientas se descubren por REFLEXIÓN ---
#
# LiteRT-LM no lee una tabla de herramientas escrita a mano: construye el JSON Schema que ve el
# modelo leyendo con kotlin-reflect el nombre de cada método anotado con @Tool, los nombres de sus
# parámetros y las descripciones de @ToolParam. R8 renombra los métodos (`getPracticeWords` -> `a`),
# borra los nombres de parámetro y estropea los metadatos de Kotlin, así que el schema sale
# inservible.
#
# **El fallo no da ningún error.** La conversación se crea con "herramientas: 1", el modelo recibe
# un esquema vacío, no ve nada que pueda llamar y se pone a conversar. Funciona en depuración y no
# en release, que es la forma más cara posible de descubrirlo: costó dos rondas de pruebas en el
# teléfono echándole la culpa al prompt.
#
# Google AI Edge Gallery no se topa con esto porque compila con `isMinifyEnabled = false`. Aquí no
# es una opción: sin minificar, el APK pasa de 27 MB a 92 MB, y este APK se reparte a mano por
# Bluetooth entre teléfonos.
-keep class * implements com.google.ai.edge.litertlm.ToolSet { *; }
-keepclassmembers class * implements com.google.ai.edge.litertlm.ToolSet { *; }
-keep,allowobfuscation @interface com.google.ai.edge.litertlm.Tool
-keep,allowobfuscation @interface com.google.ai.edge.litertlm.ToolParam

# Los atributos sin los cuales kotlin-reflect no puede reconstruir la firma de un método.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes MethodParameters, Signature, InnerClasses, EnclosingMethod, Exceptions

# kotlin-reflect lee @kotlin.Metadata para saber cómo se llaman los parámetros.
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }

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
