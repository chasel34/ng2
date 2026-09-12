plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.baselineprofile)
}

android {
  namespace = "com.chasel.ng2n.benchmark"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  targetProjectPath = ":app"

  experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

baselineProfile {
  useConnectedDevices = true
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.runner)
  implementation(libs.espresso.core)
  implementation(libs.uiautomator)
  implementation(libs.benchmark.macro.junit4)
}
