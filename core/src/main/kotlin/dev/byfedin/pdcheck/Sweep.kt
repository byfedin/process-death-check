package dev.byfedin.pdcheck

import java.nio.file.Path
import kotlin.random.Random

public data class SweepOptions(
    val packageName: String,
    /** Stop after this many process death checks. */
    val maxChecks: Int = 20,
    /** Stop after this many taps and Back presses. */
    val maxActions: Int = 60,
    /** Same seed, same app build, same device: same path through the app. */
    val seed: Int = 1,
    val killMethod: KillMethod = KillMethod.AUTO,
    val compareUi: Boolean = true,
    val settleMillis: Long = 800,
    val artifactsDir: Path? = null,
)

public data class SweepResult(val results: List<CheckResult>, val actions: Int, val screens: Int) {
    val passed: Boolean
        get() = results.all { it.passed }
}

/**
 * Explores the app by tapping what it can reach and runs a process death check on every screen it
 * finds, so restore crashes show up without writing a test per screen. A screen is checked once
 * when it appears and once more after a toggle on it changed, because untouched screens rarely lose
 * anything.
 *
 * Screens are told apart by the foreground Activity and the labels of their clickable elements, so
 * the same Compose destination with other data counts as the same screen.
 */
public class Sweep(
    private val shell: AndroidShell,
    private val clock: Clock = Clock.System,
    private val log: (String) -> Unit = {},
) {
    public fun run(options: SweepOptions): SweepResult {
        val packageName = options.packageName
        val random = Random(options.seed)
        val check = ProcessDeathCheck(shell, clock, log)
        val results = mutableListOf<CheckResult>()
        val checked = mutableSetOf<String>()
        val tried = mutableMapOf<String, MutableSet<String>>()
        val seen = mutableSetOf<String>()
        val touched = mutableSetOf<String>()
        val targetsOf = mutableMapOf<String, Set<String>>()
        var actions = 0
        // Skipped checks count too, or a screen with changing labels that never saves state
        // would be retried forever.
        var attempts = 0
        // logcat -T has one-second resolution, so a crash can show up again for the next action.
        val reported = mutableSetOf<Int>()

        shell.launchFresh(packageName, clock)
        while (actions < options.maxActions && attempts < options.maxChecks) {
            if (!shell.activities().isResumed(packageName)) {
                // A tap opened another app or closed the last Activity.
                shell.launchFresh(packageName, clock)
            }
            val screen = currentScreen(packageName) ?: break
            seen += screen.id
            targetsOf[screen.id] = screen.targets.map { it.key }.toSet()

            val checkKey = if (screen.id in touched) "${screen.id}+touched" else screen.id
            if (checkKey !in checked) {
                checked += checkKey
                attempts++
                val label =
                    if (checkKey.endsWith("+touched")) "${screen.title} (after input)"
                    else screen.title
                log("Checking $label")
                val result =
                    try {
                        check.run(
                            CheckOptions(
                                packageName,
                                killMethod = options.killMethod,
                                compareUi = options.compareUi,
                                artifactsDir = options.artifactsDir?.resolve("check-$attempts"),
                            )
                        )
                    } catch (failure: CheckException) {
                        log("Skipped $label: ${failure.message}")
                        shell.launchFresh(packageName, clock)
                        continue
                    } catch (failure: DeviceException) {
                        // One adb hiccup should not throw away the checks already done.
                        log("Skipped $label: ${failure.message}")
                        shell.launchFresh(packageName, clock)
                        continue
                    }
                result.findings.filterIsInstance<Finding.CrashOnRestore>().forEach {
                    reported += it.crash.pid
                }
                results += result.copy(name = label)
                if (!result.passed) shell.launchFresh(packageName, clock)
                continue
            }

            val untried =
                screen.targets.filter { it.key !in tried.getOrPut(screen.id) { mutableSetOf() } }
            val logcatFrom = shell.logcatTime()
            val action: String
            if (untried.isEmpty()) {
                action = "Back on ${screen.title}"
                shell.pressBack()
            } else {
                val target = untried[random.nextInt(untried.size)]
                tried.getValue(screen.id) += target.key
                if (target.node.checkable) touched += screen.id
                action =
                    "tap \"${target.node.descriptiveLabel.ifBlank { target.node.label }}\" on ${screen.title}"
                shell.tap(target.node.bounds.centerX, target.node.bounds.centerY)
            }
            actions++
            log(action.replaceFirstChar { it.uppercase() })
            clock.sleep(options.settleMillis)
            val crash =
                shell.crashesSince(logcatFrom, packageName).firstOrNull { it.pid !in reported }
            if (crash != null) {
                reported += crash.pid
                results += crashResult(packageName, screen, action, crash)
                shell.launchFresh(packageName, clock)
                continue
            }
            val explored = targetsOf.all { (id, keys) -> tried[id].orEmpty().containsAll(keys) }
            if (untried.isEmpty() && explored && !shell.activities().isResumed(packageName)) {
                log("Every reachable element was tried")
                break
            }
        }
        if (results.isEmpty()) {
            // Reporting "all passed" for zero checks would hide that nothing was tested.
            throw CheckException(
                "The sweep could not check any screen of $packageName. " +
                    "Is its UI visible to uiautomator?"
            )
        }
        return SweepResult(results, actions, seen.size)
    }

    private fun currentScreen(packageName: String): Screen? {
        val activity =
            shell.activities().resumed.firstOrNull { it.packageName == packageName } ?: return null
        val root = shell.awaitUi(packageName, clock) ?: return null
        val nodes = root.flatten().filter { it.packageName == packageName }.toList()
        val targets =
            nodes
                .filter {
                    it.clickable &&
                        it.enabled &&
                        !it.bounds.isEmpty &&
                        !it.className.endsWith("EditText")
                }
                .map { Target(it.className + ":" + it.descriptiveLabel.ifBlank { it.label }, it) }
                .distinctBy { it.key }
        val heading =
            nodes.map { it.text }.firstOrNull { it.isNotBlank() && it.length <= MAX_TITLE } ?: ""
        val activityName = activity.component.substringAfterLast('.')
        val id = activity.component + "|" + targets.map { it.key }.sorted().joinToString("|")
        val title = if (heading.isBlank()) activityName else "$activityName: $heading"
        return Screen(id, title, activity.component, targets)
    }

    private fun crashResult(packageName: String, screen: Screen, action: String, crash: Crash) =
        CheckResult(
            packageName = packageName,
            screen = screen.component,
            killMethod = KillMethod.AUTO,
            killedPids = emptyList(),
            restoredPid = null,
            findings = listOf(Finding.CrashOnInteraction(crash, action)),
            durationMillis = 0,
            artifacts = emptyMap(),
            name = "${screen.title}: $action",
        )

    private data class Screen(
        val id: String,
        val title: String,
        val component: String,
        val targets: List<Target>,
    )

    private data class Target(val key: String, val node: UiNode)

    private companion object {
        const val MAX_TITLE = 60
    }
}
