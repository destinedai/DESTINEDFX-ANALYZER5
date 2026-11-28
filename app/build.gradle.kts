import java.net.URI
import java.net.InetAddress

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  id("com.chaquo.python")
  id("com.google.protobuf") version "0.10.1"
}

val mt5ManagedServiceUrlFromGradle = providers.gradleProperty("mt5ManagedServiceUrl").orNull.orEmpty().trim()
val mt5ManagedServiceUrlFromEnv = System.getenv("MT5_MANAGED_SERVICE_URL").orEmpty().trim()
val mt5ManagedServiceUrlFromFile = rootProject.file(".env").takeIf { it.isFile }
  ?.readLines()
  ?.firstOrNull { it.trim().startsWith("MT5_MANAGED_SERVICE_URL=") }
  ?.substringAfter("=")
  ?.trim()
  ?.removeSurrounding("\"")
  ?.removeSurrounding("'")
  .orEmpty()
val mt5ManagedServiceUrl = mt5ManagedServiceUrlFromGradle.takeIf { it.isNotBlank() }
  ?: mt5ManagedServiceUrlFromEnv.takeIf { it.isNotBlank() }
  ?: mt5ManagedServiceUrlFromFile.takeIf { it.isNotBlank() }
  ?: ""
val mt5ManagedServiceUrlBuildValue = mt5ManagedServiceUrl.ifBlank { "__CONFIGURATION_REQUIRED__" }
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")
val managementApiOrigin = providers.gradleProperty("managementApiOrigin").orNull?.trim()
  ?.takeIf { it.isNotBlank() }
  ?: System.getenv("MANAGEMENT_API_ORIGIN")?.trim()?.takeIf { it.isNotBlank() }
  ?: rootProject.file(".env").takeIf { it.isFile }?.readLines()
    ?.firstOrNull { it.trim().startsWith("MANAGEMENT_API_ORIGIN=") }
    ?.substringAfter("=")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
    ?.takeIf { it.isNotBlank() }
  ?: "__CONFIGURATION_REQUIRED__"
val managementApiOriginBuildValue = managementApiOrigin.replace("\\", "\\\\").replace("\"", "\\\"")
val endpointLockRaw = providers.gradleProperty("destinedfxEndpointsLocked").orNull?.trim()
  ?: System.getenv("DESTINEDFX_ENDPOINTS_LOCKED")?.trim().orEmpty()
val codespacesHostDiscoveryRaw = providers.gradleProperty("destinedfxCodespacesHostDiscovery").orNull?.trim()
  ?: System.getenv("DESTINEDFX_CODESPACES_HOST_DISCOVERY")?.trim().orEmpty()
val destinedFxCodespacesHostDiscovery = when (codespacesHostDiscoveryRaw.lowercase()) {
  "", "false" -> false
  "true" -> true
  else -> error("destinedfxCodespacesHostDiscovery must be true or false.")
}
val destinedFxEndpointsLocked = when (endpointLockRaw.lowercase()) {
  "", "false" -> false
  "true" -> true
  else -> error("destinedfxEndpointsLocked must be true or false.")
}
require(!destinedFxCodespacesHostDiscovery || destinedFxEndpointsLocked) {
  "Codespaces host discovery is only permitted in an endpoint-locked APK."
}
val codespacesBootstrapBuild = destinedFxCodespacesHostDiscovery &&
  (mt5ManagedServiceUrl.isBlank() || mt5ManagedServiceUrl == "__CONFIGURATION_REQUIRED__") &&
  (managementApiOrigin.isBlank() || managementApiOrigin == "__CONFIGURATION_REQUIRED__")
val destinedFxDebugKeystorePath = System.getenv("DESTINEDFX_DEBUG_KEYSTORE_PATH")?.trim().orEmpty()

if (destinedFxEndpointsLocked && !codespacesBootstrapBuild) {
  fun requirePublicHttpsOrigin(raw: String, name: String) {
    val uri = runCatching { URI(raw) }.getOrNull()
      ?: error("$name must be a valid HTTPS origin.")
    val host = uri.host?.lowercase().orEmpty()
    require(uri.scheme.equals("https", true) && host.isNotBlank() && uri.userInfo == null &&
      uri.query == null && uri.fragment == null && uri.path.orEmpty().trimEnd('/').isBlank() &&
      uri.port != 0 && uri.port in -1..65535) {
      "$name must be a bare HTTPS origin without credentials, path, query or fragment."
    }
    val reservedHostSuffixes = listOf(
      ".example", ".example.com", ".example.net", ".localhost",
      ".test", ".invalid", ".local", ".internal", ".onion"
    )
    require(host !in setOf("localhost", "example.com", "example.net", "127.0.0.1", "0.0.0.0", "::1") && !host.contains("example") &&
      reservedHostSuffixes.none { host.endsWith(it) } && !host.contains("placeholder") &&
      !host.matches(Regex("^[0-9.]+$")) && !host.contains(":")) {
      "$name cannot use a placeholder, private literal or localhost address."
    }
  }
  requirePublicHttpsOrigin(mt5ManagedServiceUrl, "MT5_MANAGED_SERVICE_URL")
  requirePublicHttpsOrigin(managementApiOrigin, "MANAGEMENT_API_ORIGIN")
  require(URI(mt5ManagedServiceUrl).host.lowercase() != URI(managementApiOrigin).host.lowercase()) {
    "Bridge and management API must use the two distinct hostnames configured by the self-hosted host."
  }
}
val codespacesGithubClientIdRaw = providers.gradleProperty("codespacesGithubClientId").orNull?.trim()
  ?: System.getenv("CODESPACES_GITHUB_CLIENT_ID")?.trim()
  ?: rootProject.file(".env").takeIf { it.isFile }?.readLines()
    ?.firstOrNull { it.trim().startsWith("CODESPACES_GITHUB_CLIENT_ID=") }
    ?.substringAfter("=")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
    .orEmpty()
val codespacesGithubClientId = codespacesGithubClientIdRaw
  .takeUnless { it.isBlank() || it == "__CONFIGURATION_REQUIRED__" }
  .orEmpty()
require(codespacesGithubClientId.isBlank() || codespacesGithubClientId.matches(Regex("[A-Za-z0-9._-]{8,128}"))) {
  "CODESPACES_GITHUB_CLIENT_ID must be the public Client ID of your GitHub OAuth App."
}
val codespacesGithubClientIdBuildValue = codespacesGithubClientId.replace("\\", "\\\\").replace("\"", "\\\"")
val emulatorAcceptedRaw = providers.gradleProperty("destinedfxEmulatorAccepted").orNull?.trim()
  ?: System.getenv("DESTINEDFX_EMULATOR_ACCEPTED")?.trim().orEmpty()
val destinedFxEmulatorAccepted = when (emulatorAcceptedRaw.lowercase()) {
  "", "false" -> false
  "true" -> true
  else -> error("destinedfxEmulatorAccepted must be true or false.")
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.destinedfx.qwvzxp"
    minSdk = 24
    targetSdk = 36
    versionCode = 15
    versionName = "1.0.15"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "MT5_MANAGED_SERVICE_URL", "\"__CONFIGURATION_REQUIRED__\"")
    buildConfigField("String", "MANAGEMENT_API_ORIGIN", "\"$managementApiOriginBuildValue\"")
    buildConfigField("String", "CODESPACES_GITHUB_CLIENT_ID", "\"$codespacesGithubClientIdBuildValue\"")
    buildConfigField("boolean", "DESTINEDFX_ENDPOINTS_LOCKED", destinedFxEndpointsLocked.toString())
    buildConfigField("boolean", "DESTINEDFX_CODESPACES_HOST_DISCOVERY", destinedFxCodespacesHostDiscovery.toString())
    buildConfigField("boolean", "DESTINEDFX_EMULATOR_ACCEPTED", destinedFxEmulatorAccepted.toString())
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/upload-keystore.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "destinedfx-upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    if (destinedFxDebugKeystorePath.isNotBlank()) {
      create("destinedfxCiDebug") {
        storeFile = file(destinedFxDebugKeystorePath)
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      buildConfigField("String", "APK_BUILD_LABEL", "\"RELEASE APK - DEMO DEFAULT - REAL MT5 AVAILABLE AFTER CONFIRMATION\"")
      buildConfigField("String", "MT5_MANAGED_SERVICE_URL", "\"$mt5ManagedServiceUrlBuildValue\"")
      buildConfigField("String", "MANAGEMENT_API_ORIGIN", "\"$managementApiOriginBuildValue\"")
      buildConfigField("String", "CODESPACES_GITHUB_CLIENT_ID", "\"$codespacesGithubClientIdBuildValue\"")
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      buildConfigField("String", "APK_BUILD_LABEL", "\"DEBUG APK - DEMO DEFAULT - REAL MT5 AVAILABLE AFTER CONFIRMATION\"")
      if (destinedFxDebugKeystorePath.isNotBlank()) {
        signingConfig = signingConfigs.getByName("destinedfxCiDebug")
      }
      buildConfigField("String", "MT5_MANAGED_SERVICE_URL", "\"$mt5ManagedServiceUrlBuildValue\"")
      buildConfigField("String", "MANAGEMENT_API_ORIGIN", "\"$managementApiOriginBuildValue\"")
      buildConfigField("String", "CODESPACES_GITHUB_CLIENT_ID", "\"$codespacesGithubClientIdBuildValue\"")
    }
  }

  gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release", ignoreCase = true) }) {
      // Release signing is gated by the owner's upload key; emulator instrumentation
      // remains a separate CI result and does not gate APK assembly.
      val uploadKeystorePath = System.getenv("KEYSTORE_PATH")?.trim().orEmpty()
      require(uploadKeystorePath.isNotBlank() && file(uploadKeystorePath).isFile &&
        !System.getenv("STORE_PASSWORD").isNullOrBlank() && !System.getenv("KEY_PASSWORD").isNullOrBlank()) {
        "Production release signing requires the owner's upload keystore and passwords."
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
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all { testTask ->
        // Bound the unit-test worker on hosted runners. Without this, Dispatchers.Default
        // observes the host's very high CPU count and can spawn dozens of worker threads.
        testTask.maxHeapSize = "1536m"
        testTask.maxParallelForks = 1
        // Robolectric can leave Compose roots/animations registered after an ActivityScenario
        // test. Isolate each class so one class cannot keep later Compose tests non-idle.
        testTask.forkEvery = 1
        testTask.jvmArgs("-XX:ActiveProcessorCount=2")
        // The CI job seeds this directory with the exact instrumented Android SDK jar
        // so Robolectric does not perform one independent Maven download per test class.
        val robolectricDependencyDir = System.getenv("ROBOLECTRIC_DEPENDENCY_DIR").orEmpty()
        if (robolectricDependencyDir.isNotBlank()) {
          testTask.systemProperty("robolectric.dependency.dir", robolectricDependencyDir)
        }
        val robolectricRepository = System.getenv("ROBOLECTRIC_DEPENDENCY_REPO_URL")
          ?.trim()
          ?.takeIf { it.startsWith("https://") }
          ?: "https://repo.maven.apache.org/maven2"
        testTask.systemProperty("robolectric.dependency.repo.url", robolectricRepository)
        testTask.systemProperty("robolectric.dependency.repo.id", "maven-central")
      }
    }
  }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

chaquopy {
  defaultConfig {
    version = "3.12"
    buildPython("/usr/bin/python3.12")
    pip { install("tzdata==2025.2") }
  }
  sourceSets {
    getByName("main") {
      setSrcDirs(listOf("../bridge"))
      include("quantitative.py")
    }
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
  // SQLCipher encrypts all Room user/trade/execution records at rest; 4.17.0 supports Room 2 and Android API 23+.
  implementation("net.zetetic:sqlcipher-android:4.17.0@aar")
  implementation("androidx.sqlite:sqlite:2.6.2")
  implementation(libs.androidx.biometric)
  implementation(libs.coil.compose)
  implementation(libs.play.services.auth)
  implementation("androidx.browser:browser:1.8.0")
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation("com.google.protobuf:protobuf-kotlin-lite:4.28.3")
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
  // Pin the Robolectric native runtime module that was intermittently rate-limited by Maven Central.
  testImplementation("org.robolectric:nativeruntime-dist-compat:1.0.18")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
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

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.28.3" }
  generateProtoTasks {
    all().configureEach {
      builtins {
        create("java") { option("lite") }
      }
    }
  }
}
