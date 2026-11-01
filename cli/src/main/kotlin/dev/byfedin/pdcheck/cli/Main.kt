package dev.byfedin.pdcheck.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.byfedin.pdcheck.AdbDevice
import dev.byfedin.pdcheck.AndroidShell
import dev.byfedin.pdcheck.CheckException
import dev.byfedin.pdcheck.CheckOptions
import dev.byfedin.pdcheck.CheckResult
import dev.byfedin.pdcheck.DeviceException
import dev.byfedin.pdcheck.KillMethod
import dev.byfedin.pdcheck.ProcessDeathCheck
import dev.byfedin.pdcheck.report.Reports
import java.nio.file.Path

private class Pdcheck : CliktCommand(name = "pdcheck") {
    init {
        versionOption(Pdcheck::class.java.`package`.implementationVersion ?: "dev")
    }

    override fun help(context: Context) = "Checks that an Android app survives real process death."

    override fun run() = Unit
}

private class Kill : CliktCommand(name = "kill") {
    override fun help(context: Context) =
        """
        Kills the app on the current screen the way Android does in the background, brings it
        back and checks that the same screen returns without crashes or lost input.

        Open the screen you want to check first, or pass --launch.
        """
            .trimIndent()

    private val packageName by option("-p", "--package", help = "Application ID").required()
    private val serial by option("-s", "--serial", help = "Device serial when several are attached")
    private val launch by
        option(help = "Start the app's launcher Activity from scratch before checking").flag()
    private val method by
        option("--method", help = "How to kill the process")
            .enum<KillMethod> { it.name.lowercase().replace('_', '-') }
            .default(KillMethod.AUTO)
    private val noUiCompare by
        option("--no-ui-compare", help = "Skip comparing text fields and toggles").flag()
    private val output by
        option("-o", "--output", help = "Directory for report.json, JUnit XML and screenshots")
            .path()
            .default(Path.of("pdcheck-reports"))
    private val restoreTimeout by
        option(help = "Milliseconds to wait for the app to come back").long().default(20_000)

    override fun run() {
        val result =
            try {
                val device = AdbDevice.connect(AdbDevice.locateAdb(), serial)
                val shell = AndroidShell(device)
                if (!shell.isInstalled(packageName)) {
                    throw CheckException("$packageName is not installed on ${device.serial}.")
                }
                if (launch) shell.launchFresh(packageName)
                ProcessDeathCheck(shell, log = { echo("  $it") })
                    .run(
                        CheckOptions(
                            packageName = packageName,
                            killMethod = method,
                            compareUi = !noUiCompare,
                            restoreTimeoutMillis = restoreTimeout,
                            artifactsDir = output,
                        )
                    )
            } catch (failure: CheckException) {
                fail(failure.message)
            } catch (failure: DeviceException) {
                fail(failure.message)
            }
        Reports.write(listOf(result), output)
        print(result)
        echo("Reports: ${output.toAbsolutePath().normalize()}")
        if (!result.passed) throw ProgramResult(1)
    }

    private fun print(result: CheckResult) {
        if (result.passed) {
            echo(
                "PASS ${result.screen} restored in a new process " +
                    "(${result.killedPids.joinToString()} -> ${result.restoredPid})"
            )
            return
        }
        echo("FAIL ${result.screen}", err = true)
        for (finding in result.findings) {
            echo("  - ${finding.title}", err = true)
            finding.details.lines().take(MAX_DETAIL_LINES).forEach { echo("      $it", err = true) }
        }
    }

    private fun fail(message: String?): Nothing {
        echo("error: $message", err = true)
        throw ProgramResult(2)
    }

    private companion object {
        const val MAX_DETAIL_LINES = 12
    }
}

fun main(args: Array<String>) = Pdcheck().subcommands(Kill()).main(args)
