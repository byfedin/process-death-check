package dev.byfedin.pdcheck.report

import dev.byfedin.pdcheck.CheckResult
import dev.byfedin.pdcheck.Finding
import java.nio.file.Files
import java.nio.file.Path

/** Writes results as JSON for scripts and JUnit XML for CI test reporters. */
public object Reports {

    public fun json(results: List<CheckResult>): String = buildString {
        append("{\n  \"passed\": ").append(results.all { it.passed }).append(",\n")
        append("  \"checks\": [")
        results.forEachIndexed { index, result ->
            if (index > 0) append(",")
            append("\n    {\n")
            field("package", result.packageName)
            field("name", result.name)
            field("screen", result.screen)
            field("passed", result.passed)
            field("killMethod", result.killMethod.name)
            raw("killedPids", result.killedPids.joinToString(prefix = "[", postfix = "]"))
            raw("restoredPid", result.restoredPid?.toString() ?: "null")
            raw("durationMillis", result.durationMillis.toString())
            append("      \"findings\": [")
            result.findings.forEachIndexed { findingIndex, finding ->
                if (findingIndex > 0) append(",")
                append("\n        {\"type\": ").append(quote(finding.type))
                append(", \"title\": ").append(quote(finding.title))
                append(", \"details\": ").append(quote(finding.details)).append("}")
            }
            append(if (result.findings.isEmpty()) "],\n" else "\n      ],\n")
            append("      \"artifacts\": {")
            append(
                result.artifacts.entries.joinToString(", ") {
                    quote(it.key) + ": " + quote(it.value.toString())
                }
            )
            append("}\n    }")
        }
        append(if (results.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
    }

    public fun junit(results: List<CheckResult>, suite: String = "process-death-check"): String =
        buildString {
            val failures = results.count { !it.passed }
            val seconds = results.sumOf { it.durationMillis } / 1000.0
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<testsuite name=\"${xml(suite)}\" tests=\"${results.size}\" ")
            append("failures=\"$failures\" errors=\"0\" time=\"$seconds\">\n")
            for (result in results) {
                append("  <testcase classname=\"${xml(result.packageName)}\" ")
                append("name=\"${xml(result.name)}\" time=\"${result.durationMillis / 1000.0}\"")
                if (result.passed) {
                    append("/>\n")
                    continue
                }
                append(">\n")
                for (finding in result.findings) {
                    append("    <failure type=\"${xml(finding.type)}\" ")
                    append("message=\"${xml(finding.title)}\">")
                    append(xml(finding.details))
                    append("</failure>\n")
                }
                append("  </testcase>\n")
            }
            append("</testsuite>\n")
        }

    public fun write(results: List<CheckResult>, directory: Path) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("report.json"), json(results))
        Files.writeString(directory.resolve("TEST-process-death-check.xml"), junit(results))
    }

    private val Finding.type: String
        get() =
            when (this) {
                is Finding.CrashOnRestore -> "crash"
                is Finding.StateLost -> "state-lost"
                is Finding.WrongScreen -> "wrong-screen"
                is Finding.TaskLost -> "task-lost"
                is Finding.CrashOnInteraction -> "crash-on-interaction"
            }

    private fun StringBuilder.field(name: String, value: Any) {
        append("      ").append(quote(name)).append(": ")
        append(if (value is String) quote(value) else value.toString()).append(",\n")
    }

    private fun StringBuilder.raw(name: String, value: String) {
        append("      ").append(quote(name)).append(": ").append(value).append(",\n")
    }

    internal fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }

    private fun xml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .filter { it == '\n' || it == '\t' || it >= ' ' }
}
