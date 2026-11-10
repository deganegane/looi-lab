import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.deganegane.looilab"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.deganegane.looilab"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4"
    }

    // One permanent key, so every new APK installs over the previous one.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/looilab.jks")
            storePassword = "looilab"
            keyAlias = "looilab"
            keyPassword = "looilab"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
