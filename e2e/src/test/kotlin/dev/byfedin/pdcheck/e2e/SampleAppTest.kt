package dev.byfedin.pdcheck.e2e

import dev.byfedin.pdcheck.AdbDevice
import dev.byfedin.pdcheck.AndroidShell
import dev.byfedin.pdcheck.CheckException
import dev.byfedin.pdcheck.CheckOptions
import dev.byfedin.pdcheck.CheckResult
import dev.byfedin.pdcheck.Finding
import dev.byfedin.pdcheck.ProcessDeathCheck
import dev.byfedin.pdcheck.StateDifference
import dev.byfedin.pdcheck.Sweep
import dev.byfedin.pdcheck.SweepOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs the real check against the sample app, whose screens are correct or broken on purpose. */
class SampleAppTest {
    private val shell = AndroidShell(AdbDevice.connect(AdbDevice.locateAdb(), serial = null))

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
    fun `expected title comes back with the saved form`() {
        open("SavedFormActivity")
        val result = check(expectedTexts = listOf("Saved form"))
        assertTrue(result.passed, result.findings.joinToString { it.title + "\n" + it.details })
    }

    @Test
    fun `text that is not on screen is refused before the kill`() {
        open("SavedFormActivity")
        val error =
            assertFailsWith<CheckException> { check(expectedTexts = listOf("Not on this screen")) }
        assertTrue(error.message!!.contains("not on screen before the kill"))
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

    private fun check(expectedTexts: List<String> = emptyList()): CheckResult =
        ProcessDeathCheck(shell, log = ::println)
            .run(CheckOptions(PACKAGE, expectedTexts = expectedTexts))

    private fun open(activity: String) {
        shell.launchFresh(PACKAGE)
        shell.device.shell("am", "start", "-W", "-n", "$PACKAGE/.$activity")
        waitUntil { shell.activities().resumed.any { it.component.endsWith(".$activity") } }
    }

    private fun fillForm() {
        // On a cold CI emulator the first keystrokes can arrive before the field has focus,
        // so type until the text is really there.
        val typed =
            (1..3).any {
                val field = await { node { it.className == "android.widget.EditText" } }
                shell.tap(field.bounds.centerX, field.bounds.centerY)
                waitUntil { keyboardShown() }
                repeat(field.text.length) { shell.device.shell("input", "keyevent", "KEYCODE_DEL") }
                shell.inputText("hello")
                poll(timeoutMillis = 5_000) {
                    node { it.className == "android.widget.EditText" && it.text == "hello" }
                } != null
            }
        check(typed) { "Could not type into the sample form" }
        // Back closes the screen when no keyboard is open, so only press it when one is.
        if (keyboardShown()) shell.pressBack()
        waitUntil { !keyboardShown() }
        val toggle = await { node { it.checkable } }
        shell.tap(toggle.bounds.centerX, toggle.bounds.centerY)
        waitUntil { node { it.checkable && it.checked } != null }
    }

    private fun keyboardShown(): Boolean =
        shell.device.shell("dumpsys", "input_method").contains("mInputShown=true")

    private fun node(predicate: (dev.byfedin.pdcheck.UiNode) -> Boolean) =
        shell.uiHierarchy()?.flatten()?.firstOrNull { it.packageName == PACKAGE && predicate(it) }

    private fun <T : Any> await(probe: () -> T?): T =
        poll(timeoutMillis = 15_000, probe) ?: error("Timed out waiting for the sample app")

    private fun <T : Any> poll(timeoutMillis: Long, probe: () -> T?): T? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let {
                return it
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun waitUntil(condition: () -> Boolean) {
        await { Unit.takeIf { condition() } }
    }

    private companion object {
        const val PACKAGE = "dev.byfedin.pdcheck.sample"
    }
}
