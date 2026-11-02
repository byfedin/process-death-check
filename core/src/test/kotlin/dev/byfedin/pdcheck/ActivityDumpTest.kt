package dev.byfedin.pdcheck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityDumpTest {
    @Test
    fun `api 26 dump reports stopped activity with saved state`() {
        val dump = ActivityDump.parse(fixture("activities-api26-background.txt"))
        val record = dump.recordsOf("dev.byfedin.offercatalog").single()
        assertEquals(
            "dev.byfedin.offercatalog/dev.byfedin.offercatalog.MainActivity",
            record.component,
        )
        assertEquals(18, record.taskId)
        assertEquals("STOPPED", record.state)
        assertTrue(record.haveState)
        assertTrue(dump.isSavedInBackground("dev.byfedin.offercatalog", record.taskId))
        assertEquals("com.google.android.apps.nexuslauncher", dump.resumed.single().packageName)
    }

    @Test
    fun `api 36 dump uses mHaveState and topResumedActivity`() {
        val dump = ActivityDump.parse(fixture("activities-api36-background.txt"))
        val record = dump.recordsOf("dev.byfedin.offercatalog").single()
        assertEquals(20, record.taskId)
        assertTrue(record.haveState)
        assertTrue(dump.isSavedInBackground("dev.byfedin.offercatalog", record.taskId))
        assertFalse(dump.isResumed("dev.byfedin.offercatalog"))
    }

    @Test
    fun `every activity of the task must be saved`() {
        val dump = ActivityDump.parse(fixture("activities-api36-two-activities-background.txt"))
        val records = dump.recordsOf("dev.byfedin.pdcheck.sample")
        assertEquals(
            listOf("SavedFormActivity", "MainActivity"),
            records.map { it.component.substringAfterLast('.') },
        )
        assertTrue(records.all { it.haveState })
        assertTrue(dump.isSavedInBackground("dev.byfedin.pdcheck.sample", records.first().taskId))
    }

    @Test
    fun `foreground app is resumed and not yet saved`() {
        val dump = ActivityDump.parse(fixture("activities-api36-foreground.txt"))
        assertTrue(dump.isResumed("dev.byfedin.pdcheck.sample"))
        assertFalse(
            dump.isSavedInBackground(
                "dev.byfedin.pdcheck.sample",
                dump.resumed.first { it.packageName == "dev.byfedin.pdcheck.sample" }.taskId,
            )
        )
        assertEquals(
            "dev.byfedin.pdcheck.sample/dev.byfedin.pdcheck.sample.SavedFormActivity",
            dump.resumed.first { it.packageName == "dev.byfedin.pdcheck.sample" }.component,
        )
    }

    @Test
    fun `root of the task carries the launcher intent`() {
        val dump = ActivityDump.parse(fixture("activities-api36-foreground.txt"))
        val top = dump.resumed.first { it.packageName == "dev.byfedin.pdcheck.sample" }
        val root = dump.rootOf(top.taskId)!!
        assertEquals(
            "dev.byfedin.pdcheck.sample/dev.byfedin.pdcheck.sample.MainActivity",
            root.component,
        )
        assertEquals("android.intent.action.MAIN", root.intentAction)
        assertEquals(listOf("android.intent.category.LAUNCHER"), root.intentCategories)
    }

    @Test
    fun `finishing activities do not block the saved check`() {
        val text =
            """
            * Task{1 #7 type=standard A=10001:app U=0 visible=false sz=2}
              * Hist  #1: ActivityRecord{2 u0 app/.Closing t7}
                mHaveState=false mIcicle=null
                state=STOPPED delayedResume=false finishing=true
              * Hist  #0: ActivityRecord{1 u0 app/.Main t7}
                mHaveState=true mIcicle=Bundle[]
                state=STOPPED delayedResume=false finishing=false
            """
                .trimIndent()
        assertTrue(ActivityDump.parse(text).isSavedInBackground("app", 7))
    }

    @Test
    fun `stale tasks of the same app are ignored`() {
        val text =
            """
            * Task{1 #7 type=standard A=10001:app U=0 visible=false sz=1}
              * Hist  #0: ActivityRecord{1 u0 app/.Main t7}
                mHaveState=true mIcicle=Bundle[]
                state=STOPPED delayedResume=false finishing=false
            * Task{2 #5 type=standard A=10001:app U=0 visible=false sz=1}
              * Hist  #0: ActivityRecord{2 u0 app/.Main t5}
                mHaveState=true mIcicle=null
                state=INITIALIZING delayedResume=false finishing=false
            """
                .trimIndent()
        val dump = ActivityDump.parse(text)
        assertTrue(dump.isSavedInBackground("app", 7))
        assertFalse(dump.isSavedInBackground("app", 5))
    }

    @Test
    fun `activity below the top that was never created does not block the check`() {
        val text =
            """
            * Task{1 #7 type=standard A=10001:app U=0 visible=false sz=2}
              * Hist  #1: ActivityRecord{2 u0 app/.Detail t7}
                mHaveState=true mIcicle=Bundle[]
                state=STOPPED delayedResume=false finishing=false
              * Hist  #0: ActivityRecord{1 u0 app/.Main t7}
                mHaveState=true mIcicle=null
                state=INITIALIZING delayedResume=false finishing=false
            """
                .trimIndent()
        assertTrue(ActivityDump.parse(text).isSavedInBackground("app", 7))
    }

    @Test
    fun `activity destroyed with saved state in an earlier process death is accepted`() {
        val text =
            """
            * Task{1 #7 type=standard A=10001:app U=0 visible=false sz=2}
              * Hist  #1: ActivityRecord{2 u0 app/.Detail t7}
                mHaveState=true mIcicle=Bundle[]
                state=STOPPED delayedResume=false finishing=false
              * Hist  #0: ActivityRecord{1 u0 app/.Main t7}
                mHaveState=true mIcicle=Bundle[]
                state=DESTROYED delayedResume=false finishing=false
            """
                .trimIndent()
        assertTrue(ActivityDump.parse(text).isSavedInBackground("app", 7))
    }

    @Test
    fun `stopped activity without saved state blocks the check`() {
        val text =
            """
            * Task{1 #7 type=standard A=10001:app U=0 visible=false sz=1}
              * Hist  #0: ActivityRecord{1 u0 app/.Main t7}
                mHaveState=false mIcicle=null
                state=STOPPED delayedResume=false finishing=false
            """
                .trimIndent()
        assertFalse(ActivityDump.parse(text).isSavedInBackground("app", 7))
    }

    @Test
    fun `short component names are expanded`() {
        assertEquals("a.b/a.b.Main", ActivityDump.normalizeComponent("a.b/.Main"))
        assertEquals("a.b/c.d.Main", ActivityDump.normalizeComponent("a.b/c.d.Main"))
    }
}
