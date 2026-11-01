package dev.byfedin.pdcheck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParsersTest {
    @Test
    fun `ps output finds the app process by name`() {
        val processes = ProcessTable.parse(fixture("ps-api36.txt"))
        assertTrue(processes.size > 100)
        val app = ProcessTable.ofPackage(processes, "dev.byfedin.pdcheck.sample")
        assertEquals(1, app.size)
        assertTrue(app.single().pid > 0)
    }

    @Test
    fun `secondary processes belong to the package but similar names do not`() {
        val text =
            """
            USER           PID  PPID        VSZ    RSS WCHAN            ADDR S NAME
            u0_a218       4426   362   16841036 152340 0                   0 S com.example
            u0_a218       4500   362   16841036 152340 0                   0 S com.example:sync
            u0_a219       4600   362   16841036 152340 0                   0 S com.example.other
            """
                .trimIndent()
        val app = ProcessTable.ofPackage(ProcessTable.parse(text), "com.example")
        assertEquals(listOf(4426, 4500), app.map { it.pid })
    }

    @Test
    fun `crash report names the root cause`() {
        val crash =
            Logcat.crashes(fixture("logcat-crash-api36.txt"), "dev.byfedin.pdcheck.sample").single()
        assertEquals("dev.byfedin.pdcheck.sample", crash.processName)
        assertEquals(
            "kotlin.UninitializedPropertyAccessException: lateinit property user has not been initialized",
            crash.exception,
        )
        assertTrue(crash.trace.contains("SessionActivity.onCreate"))
    }

    @Test
    fun `crashes of other apps are ignored`() {
        assertTrue(Logcat.crashes(fixture("logcat-crash-api36.txt"), "com.example").isEmpty())
    }

    @Test
    fun `window dump exposes compose text fields and toggles`() {
        val root = UiHierarchy.parse(fixture("window-saved-form.xml"))!!
        val nodes = root.flatten().toList()
        assertTrue(nodes.any { it.className == "android.widget.EditText" })
        assertTrue(nodes.any { it.checkable })
        val snapshot = UiSnapshot.capture(root, "dev.byfedin.pdcheck.sample")
        assertEquals(mapOf("field#1" to "", "toggle:Urgent" to "unchecked"), snapshot.values)
    }

    @Test
    fun `snapshot diff reports changed and missing values`() {
        val before =
            UiSnapshot(
                mapOf(
                    "field#1" to "hello",
                    "toggle:Urgent" to "checked",
                    "selected:Travel" to "selected",
                )
            )
        val after = UiSnapshot(mapOf("field#1" to "", "toggle:Urgent" to "checked"))
        assertEquals(
            listOf(
                StateDifference("field#1", "hello", ""),
                StateDifference("selected:Travel", "selected", null),
            ),
            before.diff(after),
        )
    }

    @Test
    fun `shell arguments with spaces are quoted for the device`() {
        assertEquals("am", AdbDevice.quote("am"))
        assertEquals("'+%m-%d %H:%M'", AdbDevice.quote("+%m-%d %H:%M"))
        assertEquals("'it'\\''s'", AdbDevice.quote("it's"))
    }
}
