plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(project(":core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Needs a device with the sample installed: ./gradlew -p sample :app:installDebug
    enabled = providers.gradleProperty("e2e").isPresent
    environment("ANDROID_SERIAL", providers.gradleProperty("serial").getOrElse(""))
    outputs.upToDateWhen { false }
}
