plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "com.omega.arabify"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.omega.arabify"
  minSdk = 31
  targetSdk = 35
  ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  versionCode = 2
  versionName = "0.2.0"
 }
 buildTypes { release { isMinifyEnabled = false } }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.10.01"))
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("androidx.activity:activity-compose:1.12.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
 implementation("com.google.mlkit:translate:17.0.3")
 implementation("io.github.reandroid:ARSCLib:1.4.0")
 implementation("org.lz4:lz4-java:1.8.0")
 implementation("org.tukaani:xz:1.10")
 implementation("com.android.tools.build:apksig:8.13.2")
 debugImplementation("androidx.compose.ui:ui-tooling")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20250517")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
