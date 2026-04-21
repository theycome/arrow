plugins {
  id("arrow.kotlin")
  id(libs.plugins.spotless.get().pluginId)
}

spotless {
  kotlin {
    target("**/*.kt")
    //ktfmt()
  }
}

kotlin {
  sourceSets {
    commonMain {
      dependencies {
        api(projects.arrowAtomic)
        api(projects.arrowAnnotations)
      }
    }
    commonTest {
      dependencies {
        implementation(projects.arrowFxCoroutines)
        implementation(projects.arrowPlatform)
        implementation(libs.bundles.testing)
      }
    }
  }
}
