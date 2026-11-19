import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  implementation(project(":buffer"))
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.junit)
}
