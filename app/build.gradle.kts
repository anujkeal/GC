import java.util.Properties
plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("com.google.devtools.ksp")
}
val localProps = Properties().apply { val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
 namespace = "com.keal.gc"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.keal.gc"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "1.0.0"
  buildConfigField("String", "GOOGLE_CSE_KEY", "\"\${localProps.getProperty("GOOGLE_CSE_KEY", "")}\\"")
  buildConfigField("String", "GOOGLE_CSE_ID", "\"\${localProps.getProperty("GOOGLE_CSE_ID", "")}\\"")
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.room:room-runtime:2.6.1")
 implementation("androidx.room:room-ktx:2.6.1")
 ksp("androidx.room:room-compiler:2.6.1")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 testImplementation("junit:junit:4.13.2")
}
