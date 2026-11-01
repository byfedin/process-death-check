import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(17)
    explicitApi()
    compilerOptions {
        // The Gradle plugin loads core inside Gradle's own Kotlin runtime.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
