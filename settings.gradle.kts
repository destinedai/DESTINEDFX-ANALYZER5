pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    maven {
      name = "GoogleMavenCentralMirror"
      url = uri("https://maven-central.storage-download.googleapis.com/maven2")
    }
    maven {
      name = "MavenCentralApacheMirror"
      url = uri("https://repo.maven.apache.org/maven2")
    }
    maven {
      name = "MavenCentralRepo1Fallback"
      url = uri("https://repo1.maven.org/maven2")
    }
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    maven {
      name = "GoogleMavenCentralMirror"
      url = uri("https://maven-central.storage-download.googleapis.com/maven2")
    }
    maven {
      name = "MavenCentralApacheMirror"
      url = uri("https://repo.maven.apache.org/maven2")
    }
    maven {
      name = "MavenCentralRepo1Fallback"
      url = uri("https://repo1.maven.org/maven2")
    }
  }
}

rootProject.name = "DestinedFX"

include(":app")
