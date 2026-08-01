import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
  namespace = "pe.masi"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "pe.masi"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // LiteRT-LM trae librerías nativas para varias arquitecturas y eso infla el APK a más de
    // 100 MB. Todo teléfono capaz de mover Gemma 4 es arm64, y el APK se reparte a mano —por
    // enlace o por Bluetooth entre teléfonos, sin tienda y sin datos móviles—, así que el tamaño
    // importa de verdad.
    ndk { abiFilters += "arm64-v8a" }
  }

  buildTypes {
    release {
      // R8 se lleva por delante los miles de iconos de material-icons-extended que no se usan.
      // Sin esto el APK pasa de 90 MB; con esto baja a un tamaño que se puede mandar por
      // Bluetooth entre dos teléfonos, que es como Masi va a llegar a Huancavelica.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Firmado con la clave de debug: el APK de la demo se reparte a mano, no por Play.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

// Room escribe aquí el esquema de cada versión. Se versiona junto al código: es lo que permite
// comparar dos versiones y escribir la migración correcta cuando la tabla de tarjetas crezca.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.splashscreen)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.datastore.preferences)

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.androidx.exifinterface)

  // El motor. Lee el .litertlm; no hay servidor, no hay HTTP, no hay red.
  implementation(libs.litertlm)

  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}
