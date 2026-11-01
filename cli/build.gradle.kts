plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.clikt)
}

application {
    applicationName = "pdcheck"
    mainClass.set("dev.byfedin.pdcheck.cli.MainKt")
}

tasks.jar { manifest { attributes("Implementation-Version" to project.version) } }
