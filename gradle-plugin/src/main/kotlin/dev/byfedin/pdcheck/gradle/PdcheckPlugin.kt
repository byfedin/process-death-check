package dev.byfedin.pdcheck.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Adds `pdcheckSweep<Variant>` to Android application modules. The task installs the variant, taps
 * through the app and runs a process death check on every screen it reaches.
 */
class PdcheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("pdcheck", PdcheckExtension::class.java)
        project.plugins.withId("com.android.application") {
            val components =
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { variant ->
                val suffix = variant.name.replaceFirstChar { it.uppercase() }
                project.tasks.register("pdcheckSweep$suffix", PdcheckSweepTask::class.java) { task
                    ->
                    task.group = "verification"
                    task.description =
                        "Installs ${variant.name}, taps through it and checks every screen for process death bugs."
                    task.applicationId.set(variant.applicationId)
                    task.adb.set(components.sdkComponents.adb)
                    task.serial.set(extension.serial)
                    task.maxChecks.set(extension.maxChecks)
                    task.maxActions.set(extension.maxActions)
                    task.seed.set(extension.seed)
                    task.compareUi.set(extension.compareUi)
                    task.failOnFindings.set(extension.failOnFindings)
                    task.reportsDir.set(
                        project.layout.buildDirectory.dir("reports/pdcheck/${variant.name}")
                    )
                    // Unsigned variants have no install task. Without it the sweep would test
                    // whatever build is installed, so the task refuses to run instead.
                    val install = "install$suffix"
                    task.variantName.set(variant.name)
                    task.installable.set(project.provider { install in project.tasks.names })
                    task.dependsOn(project.tasks.matching { it.name == install })
                }
            }
        }
    }
}
