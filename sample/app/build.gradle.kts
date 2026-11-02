plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("dev.byfedin.pdcheck")
}

android {
    namespace = "dev.byfedin.pdcheck.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.byfedin.pdcheck.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}

// The sample is broken on purpose; CI asserts the findings in e2e, not here.
pdcheck { failOnFindings = false }
