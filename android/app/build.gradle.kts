import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }

android {
  namespace = "com.presentationglass.app"
  compileSdk = 36

  buildFeatures {
    buildConfig = true
    compose = true
  }

  // Lists the languages (res/values-*) in the system's per-app language setting, so that adding
  // a values-<code> folder is enough to add a language.
  androidResources {
    generateLocaleConfig = true
  }

  defaultConfig {
    applicationId = "com.presentationglass.app"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Developer Mode on the Meta AI app accepts "0" for both values.
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "0")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "0")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.material3)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.display)
  implementation(libs.mwdat.inputs)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.java.websocket)
}
