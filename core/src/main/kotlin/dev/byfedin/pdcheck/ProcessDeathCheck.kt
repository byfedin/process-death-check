package dev.byfedin.pdcheck

import java.nio.file.Path

public enum class KillMethod {
    /** `run-as` for debuggable builds, `am kill` otherwise. */
    AUTO,
    /** SIGKILL through `run-as`. Debuggable builds only; kills every process of the app. */
    RUN_AS,
    /** `am kill`, the same path the system takes for a cached app. Works for release builds. */
    AM_KILL,
}

public data class CheckOptions(
    val packageName: String,
    val killMethod: KillMethod = KillMethod.AUTO,
    val compareUi: Boolean = true,
    val saveTimeoutMillis: Long = 15_000,
    val killTimeoutMillis: Long = 10_000,
    val restoreTimeoutMillis: Long = 20_000,
    /** Time to keep watching for crashes after the Activity is resumed again. */
    val settleMillis: Long = 1_500,
    /** Where screenshots and raw dumps go; null keeps nothing on disk. */
    val artifactsDir: Path? = null,
)

public sealed interface Finding {
    public val title: String
    public val details: String

    public data class CrashOnRestore(val crash: Crash) : Finding {
        override val title: String
            get() = "Crash while restoring: ${crash.exception}"

        override val details: String
            get() = crash.trace
    }

    public data class StateLost(val differences: List<StateDifference>) : Finding {
        override val title: String
            get() = "UI state lost after process death (${differences.size})"

        override val details: String
            get() =
                differences.joinToString("\n") {
                    "${it.key}: \"${it.before}\" -> " +
                        (it.after?.let { a -> "\"$a\"" } ?: "missing")
                }
    }

    public data class WrongScreen(val expected: String, val actual: String?) : Finding {
        override val title: String
            get() = "Returned to a different screen"

        override val details: String
            get() = "Expected $expected, got ${actual ?: "no Activity of the app"}"
    }

    public data class TaskLost(val taskId: Int) : Finding {
        override val title: String
            get() = "The task was removed when the process died"

        override val details: String
            get() =
                "Task #$taskId no longer exists, so the user would start from scratch. " +
                    "Check excludeFromRecents, noHistory and finishOnTaskLaunch."
    }
}

public data class CheckResult(
    val packageName: String,
    val screen: String,
    val killMethod: KillMethod,
    val killedPids: List<Int>,
    val restoredPid: Int?,
    val findings: List<Finding>,
    val durationMillis: Long,
    val artifacts: Map<String, Path>,
) {
    val passed: Boolean
        get() = findings.isEmpty()
}

/** A check that could not run, as opposed to one that found a bug. */
public class CheckException(message: String) : RuntimeException(message)

/**
 * Sends the foreground app to the background, waits until Android holds its saved state, kills the
 * process and brings the task back. Then it verifies that the process really changed, the same
 * screen came back, nothing crashed and typed text and toggles survived.
 */
public class ProcessDeathCheck(
    private val shell: AndroidShell,
    private val clock: Clock = Clock.System,
    private val log: (String) -> Unit = {},
) {
    public fun run(options: CheckOptions): CheckResult {
        val packageName = options.packageName
        val started = clock.nowMillis()
        val artifacts = LinkedHashMap<String, Path>()

        val before = shell.activities()
        val top =
            before.resumed.firstOrNull { it.packageName == packageName }
                ?: throw CheckException(
                    "$packageName is not in the foreground. Open the screen you want to check first."
                )
        val processes = shell.processes(packageName)
        if (processes.isEmpty()) throw CheckException("$packageName has no running process.")
        val method = resolveMethod(options.killMethod, packageName)
        val snapshot =
            if (options.compareUi) UiSnapshot.capture(shell.uiHierarchy(), packageName) else null
        options.artifactsDir?.let { dir ->
            artifacts["before.png"] = dir.resolve("before.png").also(shell.device::screenshot)
        }
        log(
            "Screen: ${top.component} (task #${top.taskId}, pid ${processes.joinToString { "${it.pid}" }})"
        )

        shell.pressHome()
        val saved =
            try {
                clock.await("saved state", options.saveTimeoutMillis) {
                    shell.activities().takeIf { it.isSavedInBackground(packageName, top.taskId) }
                }
            } catch (timeout: TimeoutException) {
                val records =
                    shell
                        .activities()
                        .records
                        .filter { it.taskId == top.taskId }
                        .joinToString("; ") {
                            "${it.component.substringAfterLast('.')} ${it.state} haveState=${it.haveState}"
                        }
                throw CheckException(
                    "Android did not report saved state for $packageName within " +
                        "${options.saveTimeoutMillis} ms ($records). Is the app still visible " +
                        "(PiP, overlay)?"
                )
            }
        options.artifactsDir?.let { dir ->
            artifacts["activities-before-kill.txt"] =
                dir.resolve("activities-before-kill.txt").also {
                    java.nio.file.Files.createDirectories(dir)
                    java.nio.file.Files.writeString(it, shell.rawActivities())
                }
        }
        log(
            "Saved state acknowledged for ${saved.records.count { it.taskId == top.taskId }} Activity(s)"
        )

        val logcatFrom = shell.logcatTime()
        kill(method, packageName, processes)
        try {
            clock.await("process exit", options.killTimeoutMillis) {
                Unit.takeIf { shell.processes(packageName).isEmpty() }
            }
        } catch (timeout: TimeoutException) {
            throw CheckException(
                "$packageName is still running after ${method.name.lowercase()}. " +
                    if (method == KillMethod.AM_KILL)
                        "am kill skips processes the system considers important; try a debuggable build."
                    else "Check that no foreground service keeps it alive."
            )
        }
        log("Killed ${processes.joinToString { "${it.name} (${it.pid})" }} with $method")

        val findings = mutableListOf<Finding>()
        if (shell.activities().recordsOf(packageName).none { it.taskId == top.taskId }) {
            findings += Finding.TaskLost(top.taskId)
            return result(options, top, method, processes, null, findings, started, artifacts)
        }

        val root =
            saved.rootOf(top.taskId) ?: throw CheckException("Task #${top.taskId} has no root.")
        shell.resumeTask(root)
        val restored =
            try {
                clock.await("restore", options.restoreTimeoutMillis) {
                    val crashes = shell.crashesSince(logcatFrom, packageName)
                    if (crashes.isNotEmpty()) return@await RestoreOutcome.Crashed(crashes.first())
                    val dump = shell.activities()
                    val resumed = dump.resumed.firstOrNull { it.packageName == packageName }
                    val pid =
                        shell.processes(packageName).firstOrNull { it.name == packageName }?.pid
                    if (resumed != null && pid != null) RestoreOutcome.Resumed(resumed, pid)
                    else null
                }
            } catch (timeout: TimeoutException) {
                RestoreOutcome.Resumed(null, null)
            }

        var restoredPid: Int? = null
        when (restored) {
            is RestoreOutcome.Crashed -> findings += Finding.CrashOnRestore(restored.crash)
            is RestoreOutcome.Resumed -> {
                restoredPid = restored.pid
                if (restored.activity?.component != top.component) {
                    findings += Finding.WrongScreen(top.component, restored.activity?.component)
                } else {
                    // Crashes in onResume, LaunchedEffect or the first frame happen a bit later.
                    clock.sleep(options.settleMillis)
                    val late = shell.crashesSince(logcatFrom, packageName)
                    if (late.isNotEmpty()) {
                        findings += Finding.CrashOnRestore(late.first())
                    } else if (snapshot != null) {
                        val after = UiSnapshot.capture(shell.uiHierarchy(), packageName)
                        val lost = snapshot.diff(after)
                        if (lost.isNotEmpty()) findings += Finding.StateLost(lost)
                    }
                }
            }
        }
        if (restoredPid != null && processes.any { it.pid == restoredPid }) {
            // The kill was verified above, so this only happens with PID reuse; still fail loudly.
            throw CheckException(
                "Restored process reused pid $restoredPid; the kill cannot be proven."
            )
        }
        options.artifactsDir?.let { dir ->
            artifacts["after.png"] = dir.resolve("after.png").also(shell.device::screenshot)
        }
        return result(options, top, method, processes, restoredPid, findings, started, artifacts)
    }

    private fun resolveMethod(requested: KillMethod, packageName: String): KillMethod =
        when (requested) {
            KillMethod.AUTO ->
                if (shell.isDebuggable(packageName)) KillMethod.RUN_AS else KillMethod.AM_KILL
            KillMethod.RUN_AS ->
                requested.also {
                    if (!shell.isDebuggable(packageName)) {
                        throw CheckException("run-as needs a debuggable build of $packageName.")
                    }
                }
            KillMethod.AM_KILL -> requested
        }

    private fun kill(method: KillMethod, packageName: String, processes: List<AppProcess>) {
        when (method) {
            KillMethod.RUN_AS ->
                shell.device.shell(
                    "run-as",
                    packageName,
                    "kill",
                    "-9",
                    *processes.map { it.pid.toString() }.toTypedArray(),
                )
            KillMethod.AM_KILL -> shell.device.shell("am", "kill", packageName)
            KillMethod.AUTO -> error("AUTO is resolved before killing")
        }
    }

    private fun result(
        options: CheckOptions,
        top: ActivityRecordInfo,
        method: KillMethod,
        processes: List<AppProcess>,
        restoredPid: Int?,
        findings: List<Finding>,
        started: Long,
        artifacts: Map<String, Path>,
    ) =
        CheckResult(
            packageName = options.packageName,
            screen = top.component,
            killMethod = method,
            killedPids = processes.map { it.pid },
            restoredPid = restoredPid,
            findings = findings,
            durationMillis = clock.nowMillis() - started,
            artifacts = artifacts,
        )

    private sealed interface RestoreOutcome {
        data class Crashed(val crash: Crash) : RestoreOutcome

        data class Resumed(val activity: ActivityRecordInfo?, val pid: Int?) : RestoreOutcome
    }
}
