package dev.byfedin.pdcheck.e2e

import dev.byfedin.pdcheck.AdbDevice
import dev.byfedin.pdcheck.AndroidShell
import dev.byfedin.pdcheck.CheckOptions
import dev.byfedin.pdcheck.CheckResult
import dev.byfedin.pdcheck.Finding
import dev.byfedin.pdcheck.ProcessDeathCheck
import dev.byfedin.pdcheck.StateDifference
import dev.byfedin.pdcheck.Sweep
import dev.byfedin.pdcheck.SweepOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs the real check against the sample app, whose screens are correct or broken on purpose. */
class SampleAppTest {
    private val shell =
        AndroidShell(
            AdbDevice.connect(
                AdbDevice.locateAdb(),
                System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() },
            )
        )

    @Test
    fun `saved form survives process death`() {
        open("SavedFormActivity")
        fillForm()
        val result = check()
        assertTrue(result.passed, result.findings.joinToString { it.title + "\n" + it.details })
        assertNotEquals(result.killedPids.single(), result.restoredPid)
    }

    @Test
    fun `forgetful form loses typed text and toggle`() {
        open("ForgetfulFormActivity")
        fillForm()
        val finding = check().findings.single()
        assertIs<Finding.StateLost>(finding)
        assertEquals(
            listOf(
                StateDifference("field#1", "hello", ""),
                StateDifference("toggle:Urgent", "checked", "unchecked"),
            ),
            finding.differences,
        )
    }

    @Test
    fun `session screen crashes on restore`() {
        open("SessionActivity")
        val finding = check().findings.single()
        assertIs<Finding.CrashOnRestore>(finding)
        assertTrue(
            finding.crash.exception.startsWith("kotlin.UninitializedPropertyAccessException")
        )
    }

    @Test
    fun `sweep finds both broken screens without a script`() {
        val sweep = Sweep(shell, log = ::println).run(SweepOptions(PACKAGE))
        val failures =
            sweep.results.filterNot { it.passed }.associate { it.name to it.findings.single() }
        assertEquals(
            setOf(
                "SessionActivity: Signed in as demo",
                "ForgetfulFormActivity: Forgetful form (after input)",
            ),
            failures.keys,
        )
        assertIs<Finding.CrashOnRestore>(failures.getValue("SessionActivity: Signed in as demo"))
        assertIs<Finding.StateLost>(
            failures.getValue("ForgetfulFormActivity: Forgetful form (after input)")
        )
        assertTrue(sweep.results.any { it.passed && it.name.startsWith("SavedFormActivity") })
    }

    private fun check(): CheckResult =
        ProcessDeathCheck(shell, log = ::println).run(CheckOptions(PACKAGE))

    private fun open(activity: String) {
        shell.launchFresh(PACKAGE)
        shell.device.shell("am", "start", "-W", "-n", "$PACKAGE/.$activity")
        waitUntil { shell.activities().resumed.any { it.component.endsWith(".$activity") } }
    }

    private fun fillForm() {
        val field = await { node { it.className == "android.widget.EditText" } }
        shell.tap(field.bounds.centerX, field.bounds.centerY)
        shell.inputText("hello")
        shell.pressBack() // hide the keyboard
        waitUntil {
            node { it.className == "android.widget.EditText" && it.text == "hello" } != null
        }
        val toggle = await { node { it.checkable } }
        shell.tap(toggle.bounds.centerX, toggle.bounds.centerY)
        waitUntil { node { it.checkable && it.checked } != null }
    }

    private fun node(predicate: (dev.byfedin.pdcheck.UiNode) -> Boolean) =
        shell.uiHierarchy()?.flatten()?.firstOrNull { it.packageName == PACKAGE && predicate(it) }

    private fun <T : Any> await(probe: () -> T?): T {
        val deadline = System.nanoTime() + 15_000_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let {
                return it
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for the sample app")
    }

    private fun waitUntil(condition: () -> Boolean) {
        await { Unit.takeIf { condition() } }
    }

    private companion object {
        const val PACKAGE = "dev.byfedin.pdcheck.sample"
    }
}
