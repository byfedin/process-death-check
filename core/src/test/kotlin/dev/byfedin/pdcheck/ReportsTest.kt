package dev.byfedin.pdcheck

import dev.byfedin.pdcheck.report.Reports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportsTest {
    private val crash =
        CheckResult(
            packageName = "app",
            screen = "app/app.Detail",
            killMethod = KillMethod.RUN_AS,
            killedPids = listOf(100),
            restoredPid = null,
            findings =
                listOf(
                    Finding.CrashOnRestore(
                        Crash("app", 200, "Boom \"quoted\"", "line 1\n\tat <init>")
                    )
                ),
            durationMillis = 2500,
            artifacts = emptyMap(),
        )
    private val pass =
        crash.copy(
            screen = "app/app.Main",
            name = "app/app.Main",
            restoredPid = 200,
            findings = emptyList(),
        )

    @Test
    fun `json escapes quotes, tabs and newlines`() {
        val json = Reports.json(listOf(crash))
        assertTrue("\"passed\": false" in json)
        assertTrue("Crash while restoring: Boom \\\"quoted\\\"" in json)
        assertTrue("line 1\\n\\tat <init>" in json)
        assertTrue("\"restoredPid\": null" in json)
    }

    @Test
    fun `junit xml has one testcase per screen and escapes markup`() {
        val xml = Reports.junit(listOf(pass, crash))
        assertTrue("tests=\"2\" failures=\"1\"" in xml)
        assertTrue("<testcase classname=\"app\" name=\"app/app.Main\" time=\"2.5\"/>" in xml)
        assertTrue(
            "<failure type=\"crash\" message=\"Crash while restoring: Boom &quot;quoted&quot;\">" in
                xml
        )
        assertTrue("at &lt;init&gt;" in xml)
    }

    @Test
    fun `empty run is a valid passing report`() {
        assertEquals("{\n  \"passed\": true,\n  \"checks\": []\n}\n", Reports.json(emptyList()))
    }
}
