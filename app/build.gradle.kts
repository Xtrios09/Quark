import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val quarkFlavor = (project.findProperty("quarkFlavor") as String?)?.lowercase() ?: "play"
val isSideloadFlavor = quarkFlavor == "sideload"
val resolvedTargetSdk = if (isSideloadFlavor) 28 else 35

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.quark.x7m4kp"
    minSdk = 26
    targetSdk = resolvedTargetSdk
    versionCode = 1
    versionName = "1.0"

    buildConfigField("boolean", "CAN_EXEC_APP_BIN", if (isSideloadFlavor) "true" else "false")
    buildConfigField("String", "FLAVOR_NAME", "\"$quarkFlavor\"")
    buildConfigField("int", "TARGET_SDK_INT", resolvedTargetSdk.toString())

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
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
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      // signingConfig = signingConfigs.getByName("debugConfig")
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
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
  }
}

dependencies {
  implementation(project(":buffer"))
  implementation(project(":editor-view"))
  implementation(project(":fs"))
  implementation(project(":terminal"))
  implementation(project(":git"))
  implementation(project(":intel"))

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
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
