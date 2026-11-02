import javax.inject.Inject
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

// core is not published on its own, so its classes ship inside the plugin jar.
val bundled: Configuration by configurations.creating { isTransitive = false }

dependencies {
    compileOnly(project(":core"))
    bundled(project(":core"))
    compileOnly(libs.agp.api)
    // Gradle provides the Kotlin runtime to plugins.
    compileOnly(kotlin("stdlib"))
}

interface Injected {
    @get:Inject val archives: ArchiveOperations
}

tasks.jar {
    // Locals only: the configuration cache cannot store references to the build script.
    val archives = objects.newInstance<Injected>().archives
    val jars = bundled.elements
    from(jars.map { files -> files.map { archives.zipTree(it) } })
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
