import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Runs inside Gradle's own Kotlin runtime.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.agp.api)
}

gradlePlugin {
    website = "https://github.com/byfedin/process-death-check"
    vcsUrl = "https://github.com/byfedin/process-death-check"
    plugins {
        create("pdcheck") {
            id = "dev.byfedin.pdcheck"
            implementationClass = "dev.byfedin.pdcheck.gradle.PdcheckPlugin"
            displayName = "Process death check"
            description =
                "Installs an Android app, taps through its screens and checks that each one survives real process death."
            tags = listOf("android", "testing", "process-death")
        }
    }
}
