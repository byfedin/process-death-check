package dev.byfedin.pdcheck

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessDeathCheckTest {
    private val clock = VirtualClock()

    @Test
    fun `restored screen in a new process passes`() {
        val android = FakeAndroid()
        val result = check(android)
        assertTrue(result.passed, result.findings.toString())
        assertEquals(listOf(100), result.killedPids)
        assertEquals(200, result.restoredPid)
        assertEquals(KillMethod.RUN_AS, result.killMethod)
        assertEquals("app/app.Detail", result.screen)
    }

    @Test
    fun `kill waits until android acknowledges saved state`() {
        val android = FakeAndroid(saveDelayMillis = 3_000)
        check(android)
        val home = android.commands.indexOf("input keyevent KEYCODE_HOME")
        val kill = android.commands.indexOfFirst { it.startsWith("run-as app kill") }
        assertTrue(kill > home)
        assertTrue(android.killedAtMillis!! >= 3_000)
    }

    @Test
    fun `task is resumed with the root intent, not a new launch`() {
        val android = FakeAndroid()
        check(android)
        assertTrue(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-f 0x10200000 -n app/app.Main" in android.commands,
            android.commands.toString(),
        )
    }

    @Test
    fun `crash while restoring is reported with its cause`() {
        val android = FakeAndroid(crashOnRestore = true)
        val finding = check(android).findings.single()
        assertIs<Finding.CrashOnRestore>(finding)
        assertEquals(
            "kotlin.UninitializedPropertyAccessException: lateinit property user",
            finding.crash.exception,
        )
    }

    @Test
    fun `typed text that does not come back is reported`() {
        val android = FakeAndroid(restoredNote = "")
        val finding = check(android).findings.single()
        assertIs<Finding.StateLost>(finding)
        assertEquals(listOf(StateDifference("field:note", "hello", "")), finding.differences)
    }

    @Test
    fun `an empty window dump during the transition is not mistaken for lost state`() {
        val android = FakeAndroid(blankDumpsAfterRestore = 3)
        val result = check(android)
        assertTrue(result.passed, result.findings.toString())
    }

    @Test
    fun `ui comparison can be switched off`() {
        val android = FakeAndroid(restoredNote = "")
        assertTrue(check(android, compareUi = false).passed)
    }

    @Test
    fun `a different screen after restore is reported`() {
        val android = FakeAndroid(restoredTop = "Main")
        val finding = check(android).findings.single()
        assertIs<Finding.WrongScreen>(finding)
        assertEquals("app/app.Main", finding.actual)
    }

    @Test
    fun `a task removed with the process is reported`() {
        val android = FakeAndroid(removeTaskOnKill = true)
        assertIs<Finding.TaskLost>(check(android).findings.single())
        assertTrue(android.commands.none { it.startsWith("am start") })
    }

    @Test
    fun `release builds fall back to am kill`() {
        val android = FakeAndroid(debuggable = false)
        val result = check(android)
        assertEquals(KillMethod.AM_KILL, result.killMethod)
        assertTrue("am kill app" in android.commands)
    }

    @Test
    fun `run-as on a release build is refused`() {
        val android = FakeAndroid(debuggable = false)
        assertFailsWith<CheckException> { check(android, method = KillMethod.RUN_AS) }
    }

    @Test
    fun `app in the background is refused before anything is killed`() {
        val android = FakeAndroid(startInForeground = false)
        val error = assertFailsWith<CheckException> { check(android) }
        assertTrue(error.message!!.contains("not in the foreground"))
        assertTrue(android.commands.none { "kill" in it })
    }

    @Test
    fun `a process that survives the kill fails the check instead of passing it`() {
        val android = FakeAndroid(killIgnored = true)
        assertFailsWith<CheckException> { check(android) }
        assertTrue(android.commands.none { it.startsWith("am start") })
    }

    @Test
    fun `artifacts are written when a directory is given`() {
        val dir = kotlin.io.path.createTempDirectory("pdcheck")
        val result = check(FakeAndroid(), artifacts = dir)
        assertEquals(
            setOf("before.png", "activities-before-kill.txt", "after.png"),
            result.artifacts.keys,
        )
    }

    private fun check(
        android: FakeAndroid,
        compareUi: Boolean = true,
        method: KillMethod = KillMethod.AUTO,
        artifacts: Path? = null,
    ): CheckResult {
        android.clock = clock
        return ProcessDeathCheck(AndroidShell(android), clock)
            .run(
                CheckOptions(
                    "app",
                    killMethod = method,
                    compareUi = compareUi,
                    artifactsDir = artifacts,
                )
            )
    }
}

private class VirtualClock : Clock {
    var now = 0L

    override fun nowMillis(): Long = now

    override fun sleep(millis: Long) {
        now += millis
    }
}

/**
 * A scripted Android with a two-Activity task: app/.Main at the root and app/.Detail on top. It
 * answers the shell commands the check uses from a small state machine.
 */
private class FakeAndroid(
    private val debuggable: Boolean = true,
    private val startInForeground: Boolean = true,
    private val saveDelayMillis: Long = 0,
    private val crashOnRestore: Boolean = false,
    private val restoredNote: String = "hello",
    private val restoredTop: String = "Detail",
    private val removeTaskOnKill: Boolean = false,
    private val killIgnored: Boolean = false,
    private var blankDumpsAfterRestore: Int = 0,
) : Device {
    override val serial = "fake"
    lateinit var clock: VirtualClock
    val commands = mutableListOf<String>()
    var killedAtMillis: Long? = null

    private var foreground = startInForeground
    private var homeAtMillis: Long? = null
    private var pid: Int? = 100
    private var restored = false
    private var taskExists = true
    private var crashLog = ""
    private var top = "Detail"

    override fun execute(vararg args: String): CommandResult {
        val command = args.joinToString(" ")
        commands += command
        return when {
            command == "dumpsys activity activities" -> ok(activities())
            command == "ps -A" -> ok(processes())
            command == "run-as app true" -> CommandResult(if (debuggable) 0 else 1, "")
            command.startsWith("run-as app kill -9") -> kill()
            command == "am kill app" -> kill()
            command == "input keyevent KEYCODE_HOME" -> {
                foreground = false
                homeAtMillis = clock.now
                ok()
            }
            command.startsWith("am start") -> relaunch()
            command.startsWith("date") -> ok("11-01 10:00:00.000\n")
            command.startsWith("logcat") -> ok(crashLog)
            command.startsWith("uiautomator dump") ->
                ok("UI hierchary dumped to: /data/local/tmp/x.xml\n")
            command.startsWith("cat ") ->
                if (restored && blankDumpsAfterRestore > 0) {
                    blankDumpsAfterRestore--
                    ok(
                        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\"/>"
                    )
                } else {
                    ok(window(if (restored) restoredNote else "hello"))
                }
            command.startsWith("getprop") -> ok("36\n")
            else -> error("Unexpected command: $command")
        }
    }

    override fun screenshot(target: Path) {
        target.toFile().writeBytes(byteArrayOf(1))
    }

    private fun kill(): CommandResult {
        if (!killIgnored) {
            pid = null
            killedAtMillis = clock.now
            if (removeTaskOnKill) taskExists = false
        }
        return ok()
    }

    private fun relaunch(): CommandResult {
        pid = 200
        restored = true
        if (crashOnRestore) {
            pid = null
            crashLog =
                """
                11-01 10:00:01.000   200   200 E AndroidRuntime: FATAL EXCEPTION: main
                11-01 10:00:01.000   200   200 E AndroidRuntime: Process: app, PID: 200
                11-01 10:00:01.000   200   200 E AndroidRuntime: java.lang.RuntimeException: Unable to start activity
                11-01 10:00:01.000   200   200 E AndroidRuntime: Caused by: kotlin.UninitializedPropertyAccessException: lateinit property user
                """
                    .trimIndent()
        } else {
            foreground = true
            top = restoredTop
        }
        return ok()
    }

    private fun saved(): Boolean {
        val home = homeAtMillis ?: return false
        return clock.now - home >= saveDelayMillis
    }

    private fun activities(): String = buildString {
        if (!taskExists) {
            appendLine("  * Task{9 #1 type=home U=0 visible=true sz=1}")
            appendLine("  ResumedActivity: ActivityRecord{9 u0 launcher/.Home t1}")
            return@buildString
        }
        val records = if (top == "Detail") listOf("Detail", "Main") else listOf("Main")
        appendLine(
            "  * Task{1 #7 type=standard A=10001:app U=0 visible=$foreground sz=${records.size}}"
        )
        records.forEachIndexed { index, name ->
            val isTop = index == 0
            val state = if (isTop && foreground) "RESUMED" else "STOPPED"
            val haveState = !(isTop && foreground) && (!isTop || saved() || restored)
            appendLine(
                "    * Hist  #${records.size - 1 - index}: ActivityRecord{${10 + index} u0 app/.$name t7}"
            )
            if (name == "Main") {
                appendLine(
                    "      Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 cmp=app/.Main }"
                )
            } else {
                appendLine("      Intent { flg=0x10000000 cmp=app/.$name }")
            }
            appendLine("      mHaveState=$haveState mIcicle=null")
            appendLine("      state=$state delayedResume=false finishing=false")
        }
        val resumed =
            if (foreground) "ActivityRecord{10 u0 app/.${records.first()} t7}"
            else "ActivityRecord{9 u0 launcher/.Home t1}"
        appendLine("  ResumedActivity: $resumed")
    }

    private fun processes(): String = buildString {
        appendLine("USER           PID  PPID        VSZ    RSS WCHAN            ADDR S NAME")
        appendLine("root             1     0   10893312   4040 0                   0 S init")
        pid?.let {
            appendLine("u0_a1          $it   362   1000   1000 0                   0 S app")
        }
    }

    private fun window(note: String) =
        """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="app" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" scrollable="false" selected="false" password="false" bounds="[0,0][1080,2400]">
            <node index="0" text="$note" resource-id="app:id/note" class="android.widget.EditText" package="app" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" scrollable="false" selected="false" password="false" bounds="[0,100][1080,200]" />
          </node>
        </hierarchy>
        """
            .trimIndent()

    private fun ok(output: String = "") = CommandResult(0, output)
}
