package dev.byfedin.pdcheck

public data class Crash(
    val processName: String,
    val pid: Int,
    val exception: String,
    val trace: String,
)

/** Extracts `AndroidRuntime` fatal exceptions from `logcat -v threadtime` output. */
public object Logcat {
    // 11-01 23:50:00.123  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
    private val line =
        Regex(
            """^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[VDIWEF]\s+AndroidRuntime\s*:\s?(.*)$"""
        )
    private val processLine = Regex("""^Process: ([^,]+), PID: (\d+)""")

    public fun crashes(text: String, packageName: String): List<Crash> {
        val byPid = LinkedHashMap<Int, MutableList<String>>()
        val result = mutableListOf<Crash>()
        fun flush(pid: Int) {
            val messages = byPid.remove(pid) ?: return
            val process = messages.firstNotNullOfOrNull { processLine.find(it) }
            val name = process?.groupValues?.get(1) ?: return
            if (name != packageName && !name.startsWith("$packageName:")) return
            val stack = messages.dropWhile { !it.startsWith("Process:") }.drop(1)
            result +=
                Crash(
                    processName = name,
                    pid = process.groupValues[2].toInt(),
                    // The root cause says more than "Unable to start activity".
                    exception =
                        stack
                            .lastOrNull { it.startsWith("Caused by: ") }
                            ?.removePrefix("Caused by: ") ?: stack.firstOrNull().orEmpty(),
                    trace = stack.joinToString("\n"),
                )
        }
        for (raw in text.lineSequence()) {
            val match = line.find(raw) ?: continue
            val pid = match.groupValues[1].toInt()
            val message = match.groupValues[2]
            if (message.startsWith("FATAL EXCEPTION")) {
                flush(pid)
                byPid[pid] = mutableListOf()
            } else {
                byPid[pid]?.add(message)
            }
        }
        byPid.keys.toList().forEach(::flush)
        return result
    }
}
