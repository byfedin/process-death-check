package dev.byfedin.pdcheck

public data class AppProcess(val pid: Int, val name: String)

/** Parses `ps -A` (toybox) output. Columns differ between releases, so the header decides. */
public object ProcessTable {
    public fun parse(text: String): List<AppProcess> {
        val lines = text.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.trim()?.split(Regex("\\s+")) ?: return emptyList()
        val pidColumn = header.indexOf("PID")
        if (pidColumn < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            val pid = columns.getOrNull(pidColumn)?.toIntOrNull() ?: return@mapNotNull null
            AppProcess(pid, columns.last())
        }
    }

    /** The main process and any `pkg:name` processes declared with `android:process`. */
    public fun ofPackage(processes: List<AppProcess>, packageName: String): List<AppProcess> =
        processes.filter { it.name == packageName || it.name.startsWith("$packageName:") }
}
