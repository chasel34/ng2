plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
  alias(libs.plugins.room)
  alias(libs.plugins.baselineprofile)
}

val koogReleaseSmoke = providers.gradleProperty("testBuildType").orNull == "release"

android {
  namespace = "com.chasel.ng2n"
  testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "com.chasel.ng2"
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    versionCode = 6
    versionName = "0.3.0"

    testInstrumentationRunner = if (koogReleaseSmoke) {
      "com.chasel.ng2n.ai.KoogSmokeInstrumentation"
    } else {
      "androidx.test.runner.AndroidJUnitRunner"
    }
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
      testProguardFiles("proguard-test-rules.pro")
      if (koogReleaseSmoke) proguardFiles("proguard-koog-smoke-rules.pro")
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

  if (koogReleaseSmoke) {
    sourceSets.getByName("main").kotlin.directories += "src/koogTest/kotlin"
    sourceSets.getByName("release").manifest.srcFile("src/koogSmoke/AndroidManifest.xml")
    sourceSets.getByName("release").res.directories += "src/koogSmoke/res"
  }
  sourceSets.getByName("test").kotlin.directories += "src/koogTest/kotlin"
  sourceSets.getByName("androidTest") {
    assets.directories += "$projectDir/schemas"
    if (koogReleaseSmoke) {
      kotlin.directories.clear()
      java.directories.clear()
      java.directories += "src/koogAndroidTest/java"
    }
  }
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

  implementation(platform(libs.okhttp.bom))
  implementation(libs.okhttp)
  implementation(libs.koog.core)
  implementation(libs.koog.chat.memory)
  implementation(libs.koog.persistence)
  implementation(libs.koog.events)
  implementation(libs.koog.skills)
  implementation(libs.koog.file.tools)
  implementation(libs.koog.deepseek)
  implementation(libs.koog.http.okhttp)
  implementation(libs.okhttp.coroutines)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.coil.gif)

  implementation(libs.kotlin.reflect)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.collections.immutable)

  implementation(libs.profileinstaller)

  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver3)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.espresso.core)
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.room.testing)

  if (koogReleaseSmoke) {
    implementation(libs.junit)
    implementation(libs.mockwebserver3)
    implementation(libs.kotlinx.coroutines.test)
  }

  baselineProfile(project(":benchmark"))
}
