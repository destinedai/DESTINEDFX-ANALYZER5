import java.net.URI
import java.net.InetAddress

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}

val mt5ManagedServiceUrlFromEnv = System.getenv("MT5_MANAGED_SERVICE_URL").orEmpty().trim()
val mt5ManagedServiceUrlFromFile = rootProject.file(".env").takeIf { it.isFile }
  ?.readLines()
  ?.firstOrNull { it.trim().startsWith("MT5_MANAGED_SERVICE_URL=") }
  ?.substringAfter("=")
  ?.trim()
  ?.removeSurrounding("\"")
  ?.removeSurrounding("'")
  .orEmpty()
val mt5ManagedServiceUrl = mt5ManagedServiceUrlFromEnv.takeIf { it.isNotBlank() }
  ?: mt5ManagedServiceUrlFromFile.takeIf { it.isNotBlank() }
  ?: ""
val mt5ManagedServiceUrlBuildValue = mt5ManagedServiceUrl.ifBlank { "__CONFIGURATION_REQUIRED__" }
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.destinedfx.qwvzxp"
    minSdk = 24
    targetSdk = 36
    versionCode = 9
    versionName = "1.0.9"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "MT5_MANAGED_SERVICE_URL", "\"$mt5ManagedServiceUrlBuildValue\"")
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }

  gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release", ignoreCase = true) }) {
      val uri = runCatching { URI(mt5ManagedServiceUrl) }.getOrNull()
      val host = uri?.host?.lowercase().orEmpty()
      val port = runCatching { uri?.port ?: -2 }.getOrNull() ?: -2
      val literalAddress = if (host.matches(Regex("^[0-9a-fA-F:.]+$"))) runCatching { InetAddress.getByName(host) }.getOrNull() else null
      val specialIpv4 = Regex("^(0|10|127|169)\\..*|^100\\.(6[4-9]|[7-9][0-9])\\..*|^172\\.(1[6-9]|2[0-9]|3[01])\\..*|^192\\.168\\..*|^198\\.(18|19)\\..*|^198\\.51\\.100\\..*|^203\\.0\\.113\\..*|^224\\..*").matches(host)
      val mappedIpv6 = host.lowercase().startsWith("::ffff:")
      require(uri != null && uri.scheme.equals("https", ignoreCase = true) && host.isNotBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && uri.path.orEmpty().trimEnd('/').isBlank() && port != 0 && port in -1..65535) {
        "Release builds require MT5_MANAGED_SERVICE_URL to be a public HTTPS origin (no path, credentials, query, or fragment)."
      }
      require(host !in setOf("example.com", "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]") &&
        !(literalAddress == null && (host.contains(":") || host.matches(Regex("^[0-9.]+$")))) &&
        !specialIpv4 && !mappedIpv6 &&
        literalAddress?.let { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress } != false &&
        !host.endsWith(".example") && !host.endsWith(".example.com") && !host.endsWith(".localhost") &&
        !host.endsWith(".test") && !host.endsWith(".invalid") && !host.contains("placeholder")) {
        "Release builds cannot use a placeholder managed-service host."
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Dependencies
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.biometric)
  implementation(libs.coil.compose)
  implementation(libs.play.services.auth)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  // Bundled, offline on-device OCR for chart labels; no screenshots are sent to a service.
  implementation("com.google.mlkit:text-recognition:16.0.1")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation("org.json:json:20231013")
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
