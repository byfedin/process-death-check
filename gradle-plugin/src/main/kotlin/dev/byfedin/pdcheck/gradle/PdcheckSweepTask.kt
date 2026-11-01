package dev.byfedin.pdcheck.gradle

import dev.byfedin.pdcheck.AdbDevice
import dev.byfedin.pdcheck.AndroidShell
import dev.byfedin.pdcheck.CheckException
import dev.byfedin.pdcheck.DeviceException
import dev.byfedin.pdcheck.Sweep
import dev.byfedin.pdcheck.SweepOptions
import dev.byfedin.pdcheck.report.Reports
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "The result depends on the device, not on task inputs")
abstract class PdcheckSweepTask : DefaultTask() {
    @get:Input abstract val applicationId: Property<String>

    @get:Input abstract val variantName: Property<String>

    /** False for variants AGP cannot install, usually unsigned release builds. */
    @get:Input abstract val installable: Property<Boolean>

    @get:Internal abstract val adb: RegularFileProperty

    @get:Input @get:Optional abstract val serial: Property<String>

    @get:Input abstract val maxChecks: Property<Int>

    @get:Input abstract val maxActions: Property<Int>

    @get:Input abstract val seed: Property<Int>

    @get:Input abstract val compareUi: Property<Boolean>

    @get:Input abstract val failOnFindings: Property<Boolean>

    @get:OutputDirectory abstract val reportsDir: DirectoryProperty

    @TaskAction
    fun sweep() {
        if (!installable.get()) {
            throw GradleException(
                "Variant ${variantName.get()} cannot be installed, usually because it has no " +
                    "signing config. Sign it or run the sweep on a debug variant."
            )
        }
        val output = reportsDir.get().asFile.toPath()
        // Screenshots from an earlier run must not sit next to this run's report.
        reportsDir.get().asFile.listFiles()?.forEach { it.deleteRecursively() }
        val sweep =
            try {
                val device = AdbDevice.connect(adb.get().asFile.toPath(), serial.orNull)
                Sweep(AndroidShell(device), log = { logger.lifecycle("  $it") })
                    .run(
                        SweepOptions(
                            packageName = applicationId.get(),
                            maxChecks = maxChecks.get(),
                            maxActions = maxActions.get(),
                            seed = seed.get(),
                            compareUi = compareUi.get(),
                            artifactsDir = output,
                        )
                    )
            } catch (failure: CheckException) {
                throw GradleException(
                    failure.message ?: "Process death check could not run",
                    failure,
                )
            } catch (failure: DeviceException) {
                throw GradleException(failure.message ?: "Device error", failure)
            }
        Reports.write(sweep.results, output)
        val failed = sweep.results.filterNot { it.passed }
        for (result in sweep.results) {
            logger.lifecycle((if (result.passed) "PASS " else "FAIL ") + result.name)
            result.findings.forEach { logger.lifecycle("  - ${it.title}") }
        }
        val summary =
            "${sweep.results.size} checks on ${sweep.screens} screens, ${failed.size} failed. " +
                "Report: ${output.resolve("report.json").toUri()}"
        if (failed.isNotEmpty() && failOnFindings.get()) throw GradleException(summary)
        logger.lifecycle(summary)
    }
}
