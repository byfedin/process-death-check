package dev.byfedin.pdcheck

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

public data class CommandResult(val exitCode: Int, val output: String) {
    val succeeded: Boolean
        get() = exitCode == 0
}

public class DeviceException(message: String) : RuntimeException(message)

/** The device operations the checks need. Tests replace it with a scripted fake. */
public interface Device {
    public val serial: String

    /** Runs a shell command on the device and returns its exit code and combined output. */
    public fun execute(vararg args: String): CommandResult

    /** Writes a PNG screenshot of the current screen to [target]. */
    public fun screenshot(target: Path)

    /** Runs a shell command and fails on a non-zero exit code. */
    public fun shell(vararg args: String): String {
        val result = execute(*args)
        if (!result.succeeded) {
            throw DeviceException(
                "`${args.joinToString(" ")}` failed with ${result.exitCode}: ${result.output.trim()}"
            )
        }
        return result.output
    }
}

/** A device reached through the `adb` binary. */
public class AdbDevice(
    private val adb: Path,
    override val serial: String,
    private val timeoutSeconds: Long = 30,
) : Device {

    override fun execute(vararg args: String): CommandResult =
        run(listOf("shell", args.joinToString(" ") { quote(it) }))

    override fun screenshot(target: Path) {
        Files.createDirectories(target.toAbsolutePath().parent)
        val process =
            ProcessBuilder(adb.toString(), "-s", serial, "exec-out", "screencap", "-p")
                .redirectOutput(target.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw DeviceException("Screenshot timed out on $serial")
        }
    }

    private fun run(arguments: List<String>): CommandResult {
        val result =
            runProcess(listOf(adb.toString(), "-s", serial) + arguments, timeoutSeconds)
                ?: throw DeviceException(
                    "adb timed out after ${timeoutSeconds}s: ${arguments.joinToString(" ")}"
                )
        return CommandResult(result.exitCode, result.stdout + result.stderr)
    }

    public companion object {
        private val safe = Regex("""[A-Za-z0-9_./:=@%+,-]+""")

        /** Quotes an argument for the device's `sh`, since adb joins arguments with spaces. */
        public fun quote(argument: String): String =
            if (argument.matches(safe)) argument else "'" + argument.replace("'", "'\\''") + "'"

        /** Finds `adb` through ANDROID_HOME, ANDROID_SDK_ROOT or PATH. */
        public fun locateAdb(environment: Map<String, String> = System.getenv()): Path {
            val executable =
                if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"
            val candidates =
                listOfNotNull(environment["ANDROID_HOME"], environment["ANDROID_SDK_ROOT"]).map {
                    Path.of(it, "platform-tools", executable)
                } +
                    environment["PATH"].orEmpty().split(java.io.File.pathSeparator).map {
                        Path.of(it, executable)
                    }
            return candidates.firstOrNull { Files.isExecutable(it) }
                ?: throw DeviceException(
                    "adb not found. Set ANDROID_HOME or add platform-tools to PATH."
                )
        }

        /**
         * Connects to [serial], then to `ANDROID_SERIAL`, then to the only attached device. Several
         * devices without a serial is an error rather than a guess.
         */
        public fun connect(
            adb: Path,
            serial: String?,
            environment: Map<String, String> = System.getenv(),
        ): AdbDevice {
            val selected = serial ?: environment["ANDROID_SERIAL"]?.takeIf { it.isNotBlank() }
            if (selected != null) {
                // stdout carries only the state; "daemon started" notes go to stderr.
                var state = adbOutput(adb, "-s", selected, "get-state")
                // A freshly started adb server reports devices as offline for a few seconds.
                repeat(OFFLINE_RETRIES) {
                    if (!state.stderr.contains("offline")) return@repeat
                    Thread.sleep(OFFLINE_RETRY_MILLIS)
                    state = adbOutput(adb, "-s", selected, "get-state")
                }
                if (state.stdout.trim().lines().lastOrNull() != "device") {
                    throw DeviceException(
                        "Device $selected is not available: " + (state.stderr + state.stdout).trim()
                    )
                }
                return AdbDevice(adb, selected)
            }
            val devices =
                adbOutput(adb, "devices")
                    .stdout
                    .lines()
                    .drop(1)
                    .map { it.trim().split(Regex("\\s+")) }
                    .filter { it.size >= 2 && it[1] == "device" }
                    .map { it[0] }
            return when (devices.size) {
                1 -> AdbDevice(adb, devices.single())
                0 ->
                    throw DeviceException(
                        "No device attached. Start an emulator or connect a phone."
                    )
                else ->
                    throw DeviceException(
                        "Several devices attached (${devices.joinToString()}). Pass --serial."
                    )
            }
        }

        private const val OFFLINE_RETRIES = 10
        private const val OFFLINE_RETRY_MILLIS = 1_000L

        private fun adbOutput(adb: Path, vararg arguments: String): ProcessOutput =
            runProcess(listOf(adb.toString()) + arguments, timeoutSeconds = 30)
                ?: throw DeviceException("adb timed out: ${arguments.joinToString(" ")}")
    }
}

internal class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs [command] and returns null on timeout. Both streams are drained on their own threads: a full
 * pipe would block the child, and a hung adb that never closes its output must still time out.
 */
internal fun runProcess(command: List<String>, timeoutSeconds: Long): ProcessOutput? {
    val process = ProcessBuilder(command).start()
    var stdout = ""
    var stderr = ""
    val readers =
        listOf(
            thread(isDaemon = true) { stdout = process.inputStream.bufferedReader().readText() },
            thread(isDaemon = true) { stderr = process.errorStream.bufferedReader().readText() },
        )
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    readers.forEach { it.join() }
    return ProcessOutput(process.exitValue(), stdout, stderr)
}
