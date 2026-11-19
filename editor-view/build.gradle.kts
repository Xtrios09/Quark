plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.quark.editor"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  api(project(":buffer"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  testImplementation(libs.junit)
}
