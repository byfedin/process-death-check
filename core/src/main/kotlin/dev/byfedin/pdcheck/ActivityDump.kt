package dev.byfedin.pdcheck

/** One `ActivityRecord` from `dumpsys activity activities`. */
public data class ActivityRecordInfo(
    val id: String,
    val component: String,
    val taskId: Int,
    /** Lifecycle state as reported by the system server, for example `RESUMED` or `STOPPED`. */
    val state: String?,
    /** True once the system holds the Activity's saved instance state. */
    val haveState: Boolean,
    val finishing: Boolean,
    /** Action and categories of the Intent that started this Activity, when printed. */
    val intentAction: String? = null,
    val intentCategories: List<String> = emptyList(),
) {
    val packageName: String
        get() = component.substringBefore('/')

    internal val isStopped: Boolean
        get() = state.equals("STOPPED", ignoreCase = true)

    /** A record below the top that was never created holds no state that could be lost. */
    internal val neverLaunched: Boolean
        get() = state.equals("INITIALIZING", ignoreCase = true)
}

/** The parts of `dumpsys activity activities` that process death checks rely on. */
public data class ActivityDump(
    val records: List<ActivityRecordInfo>,
    val resumed: List<ActivityRecordInfo>,
) {
    public fun recordsOf(packageName: String): List<ActivityRecordInfo> =
        records.filter { it.packageName == packageName }

    /** The bottom Activity of a task; its Intent identifies the task to the launcher. */
    public fun rootOf(taskId: Int): ActivityRecordInfo? = records.lastOrNull { it.taskId == taskId }

    public fun isResumed(packageName: String): Boolean =
        resumed.any { it.packageName == packageName }

    /**
     * True when every live Activity of [taskId] is stopped and its state has been handed to the
     * system. Killing earlier tests a cold start, not process death. Other tasks of the same app
     * are ignored: after a force-stop, recents keeps tasks whose records never start again.
     */
    public fun isSavedInBackground(packageName: String, taskId: Int): Boolean {
        if (isResumed(packageName)) return false
        val live = records.filter { it.taskId == taskId && !it.finishing }
        return live.isNotEmpty() &&
            live.any { it.isStopped } &&
            live.all { (it.isStopped && it.haveState) || it.neverLaunched }
    }

    public companion object {
        // API 26: "* Hist #0: ActivityRecord{25af34b u0 pkg/.Main t18}"
        // API 29+: "* Hist  #0: ActivityRecord{212079964 u0 pkg/.Main t20}"
        private val historyHeader =
            Regex("""^\s*\*\s*Hist\s+#\d+:\s*ActivityRecord\{(\S+) u\d+ (\S+) t(-?\d+)[^}]*}""")
        private val blockEnd = Regex("""^\s*\*\s*(Hist|Task|TaskRecord|TaskFragment)\b|^\S""")
        private val stateField = Regex("""(?<![A-Za-z])state=([A-Z_]+)""")
        private val haveStateField = Regex("""\b(?:mHaveState|haveState)=(true|false)""")
        private val finishingField = Regex("""\bfinishing=(true|false)""")
        private val intentField = Regex("""(?:Intent \{|intent=\{)([^}]*)""")
        private val actionField = Regex("""\bact=(\S+)""")
        private val categoriesField = Regex("""\bcat=\[([^\]]*)]""")
        private val resumedLine =
            Regex(
                """^\s*(?:mResumedActivity|ResumedActivity|topResumedActivity|Resumed)\s*[:=]\s*ActivityRecord\{(\S+) u\d+ (\S+) t(-?\d+)[^}]*}"""
            )

        public fun parse(text: String): ActivityDump {
            val lines = text.lines()
            val records = LinkedHashMap<String, ActivityRecordInfo>()
            val resumed = LinkedHashMap<String, ActivityRecordInfo>()
            var index = 0
            while (index < lines.size) {
                val header = historyHeader.find(lines[index])
                if (header == null) {
                    index++
                    continue
                }
                val (id, component, task) = header.destructured
                val body = StringBuilder()
                index++
                while (index < lines.size && !blockEnd.containsMatchIn(lines[index])) {
                    body.appendLine(lines[index])
                    index++
                }
                // The same record can be printed in more than one section; the first one wins.
                val intent = intentField.find(body)?.groupValues?.get(1)
                records.getOrPut(id) {
                    ActivityRecordInfo(
                        id = id,
                        component = normalizeComponent(component),
                        taskId = task.toInt(),
                        state = stateField.find(body)?.groupValues?.get(1),
                        haveState = haveStateField.find(body)?.groupValues?.get(1) == "true",
                        finishing = finishingField.find(body)?.groupValues?.get(1) == "true",
                        intentAction = intent?.let { actionField.find(it)?.groupValues?.get(1) },
                        intentCategories =
                            intent
                                ?.let { categoriesField.find(it)?.groupValues?.get(1) }
                                ?.split(',')
                                ?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                                .orEmpty(),
                    )
                }
            }
            for (line in lines) {
                val match = resumedLine.find(line) ?: continue
                val (id, component, task) = match.destructured
                resumed.getOrPut(id) {
                    records[id]
                        ?: ActivityRecordInfo(
                            id,
                            normalizeComponent(component),
                            task.toInt(),
                            "RESUMED",
                            haveState = false,
                            finishing = false,
                        )
                }
            }
            return ActivityDump(records.values.toList(), resumed.values.toList())
        }

        /** Expands `pkg/.Main` to `pkg/pkg.Main` so components compare reliably. */
        public fun normalizeComponent(component: String): String {
            val packageName = component.substringBefore('/')
            val className = component.substringAfter('/', missingDelimiterValue = "")
            return if (className.startsWith(".")) "$packageName/$packageName$className"
            else component
        }
    }
}
