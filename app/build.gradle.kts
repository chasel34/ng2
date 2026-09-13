plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
  alias(libs.plugins.room)
  alias(libs.plugins.baselineprofile)
}

android {
  namespace = "com.chasel.ng2n"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "com.chasel.ng2"
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    versionCode = 5
    versionName = "0.2.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    getByName("debug") {
      signingConfig = signingConfigs.getByName("debug")
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
    }
    getByName("release") {
      signingConfig = signingConfigs.getByName("debug")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

room {
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  implementation(platform(libs.compose.bom))
  androidTestImplementation(platform(libs.compose.bom))

  implementation(libs.core.ktx)
  implementation(libs.activity.compose)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.ui.tooling)
  debugImplementation(libs.compose.ui.test.manifest)

  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.viewmodel.nav3)
  implementation(libs.nav3.runtime)
  implementation(libs.nav3.ui)

  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  ksp(libs.hilt.compiler)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.datastore.preferences)

  implementation(libs.okhttp)
  implementation(libs.okhttp.coroutines)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.coil.gif)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.collections.immutable)

  implementation(libs.profileinstaller)

  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver3)
  testImplementation(libs.kotlin.reflect)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.espresso.core)
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.room.testing)

  baselineProfile(project(":benchmark"))
}
