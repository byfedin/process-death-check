package dev.byfedin.pdcheck

/** Typed wrappers over the shell commands the checks use. */
public class AndroidShell(public val device: Device) {

    public fun activities(): ActivityDump =
        ActivityDump.parse(device.shell("dumpsys", "activity", "activities"))

    public fun rawActivities(): String = device.shell("dumpsys", "activity", "activities")

    public fun processes(packageName: String): List<AppProcess> =
        ProcessTable.ofPackage(ProcessTable.parse(device.shell("ps", "-A")), packageName)

    public fun sdkLevel(): Int = device.shell("getprop", "ro.build.version.sdk").trim().toInt()

    public fun model(): String = device.shell("getprop", "ro.product.model").trim()

    public fun isInstalled(packageName: String): Boolean =
        device.execute("pm", "path", packageName).output.contains("package:")

    /** `run-as` succeeds only for debuggable packages, which is exactly what killing needs. */
    public fun isDebuggable(packageName: String): Boolean =
        device.execute("run-as", packageName, "true").succeeded

    public fun launcherActivity(packageName: String): String {
        val output =
            device
                .execute(
                    "cmd",
                    "package",
                    "resolve-activity",
                    "--brief",
                    "-a",
                    "android.intent.action.MAIN",
                    "-c",
                    "android.intent.category.LAUNCHER",
                    packageName,
                )
                .output
        val component =
            output.lines().map { it.trim() }.lastOrNull { it.startsWith("$packageName/") }
        return component?.let(ActivityDump::normalizeComponent)
            ?: throw DeviceException("$packageName has no launcher Activity. Is it installed?")
    }

    /**
     * Starts [component] the way a launcher does. With an existing task this brings it to the front
     * and Android recreates the top Activity from its saved state.
     */
    public fun launchFromLauncher(component: String) {
        device.shell(
            "am",
            "start",
            // Wait until the Activity has drawn, so the next command sees a created Activity.
            "-W",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            "-f",
            "0x10200000",
            "-n",
            component,
        )
    }

    /**
     * Brings an existing task back the way tapping its launcher icon does: an Intent equal to the
     * task's root Intent with NEW_TASK | RESET_TASK_IF_NEEDED. Android then resumes the task and
     * recreates its top Activity instead of starting a new one.
     */
    public fun resumeTask(root: ActivityRecordInfo) {
        val intent = buildList {
            root.intentAction?.let { addAll(listOf("-a", it)) }
            root.intentCategories.forEach { addAll(listOf("-c", it)) }
        }
        device.shell(
            "am",
            "start",
            *intent.toTypedArray(),
            "-f",
            "0x10200000",
            "-n",
            root.component,
        )
    }

    /** Force-stops the app and starts its launcher Activity in a fresh task. */
    public fun launchFresh(
        packageName: String,
        clock: Clock = Clock.System,
        timeoutMillis: Long = 15_000,
    ) {
        forceStop(packageName)
        launchFromLauncher(launcherActivity(packageName))
        try {
            clock.await("launch", timeoutMillis) {
                Unit.takeIf { activities().isResumed(packageName) }
            }
        } catch (timeout: TimeoutException) {
            throw CheckException(
                "$packageName did not reach the foreground within $timeoutMillis ms."
            )
        }
    }

    public fun forceStop(packageName: String) {
        device.shell("am", "force-stop", packageName)
    }

    public fun pressHome() {
        device.shell("input", "keyevent", "KEYCODE_HOME")
    }

    public fun pressBack() {
        device.shell("input", "keyevent", "KEYCODE_BACK")
    }

    public fun tap(x: Int, y: Int) {
        device.shell("input", "tap", x.toString(), y.toString())
    }

    /** Types ASCII text into the focused field. `input text` treats spaces as separators. */
    public fun inputText(text: String) {
        device.shell("input", "text", text.replace(" ", "%s"))
    }

    /** Device wall-clock time in the format `logcat -T` accepts. */
    public fun logcatTime(): String = device.shell("date", "+%m-%d %H:%M:%S.000").trim()

    public fun crashesSince(time: String, packageName: String): List<Crash> {
        val output =
            device.execute("logcat", "-d", "-b", "crash", "-v", "threadtime", "-T", time).output
        return Logcat.crashes(output, packageName)
    }

    /** Returns the current window hierarchy, or null while the window has no root yet. */
    public fun uiHierarchy(): UiNode? {
        val path = "/data/local/tmp/pdcheck-window.xml"
        val status = device.execute("uiautomator", "dump", path).output
        if (!status.contains("dumped to")) return null
        val xml = device.shell("cat", path)
        return runCatching { UiHierarchy.parse(xml) }.getOrNull()
    }
}
