# process-death-check

[![CI](https://github.com/byfedin/process-death-check/actions/workflows/ci.yml/badge.svg)](https://github.com/byfedin/process-death-check/actions/workflows/ci.yml)

`pdcheck` kills your Android app the way the system does in the background, brings it back and tells you whether the same screen returned in a new process, without crashes and without losing what the user typed.

```text
$ pdcheck kill -p dev.byfedin.pdcheck.sample
  Screen: dev.byfedin.pdcheck.sample/dev.byfedin.pdcheck.sample.ForgetfulFormActivity (task #845, pid 5244)
  Saved state acknowledged for 2 Activity(s)
  Killed dev.byfedin.pdcheck.sample (5244) with RUN_AS
FAIL dev.byfedin.pdcheck.sample/dev.byfedin.pdcheck.sample.ForgetfulFormActivity
  - UI state lost after process death (2)
      field#1: "hello" -> ""
      toggle:Urgent: "checked" -> "unchecked"
```

## Why

Users report process death bugs as "the app forgot what I was doing" or as a crash nobody can reproduce. The usual checks miss them:

| Check | What it misses |
| --- | --- |
| Rotation, `ActivityScenario.recreate()` | The process survives, so ViewModels and singletons keep their data |
| "Don't keep activities" | Destroys Activities, not the process |
| Logcat's Stop button, `am kill` by hand | Manual, and easy to do before the state is saved |
| A scripted kill in a UI test | Passes even when the kill never happened or came too early |

`pdcheck` does the kill in the right order and proves it worked:

1. Records the foreground screen, its task and process IDs, the text in its fields and the state of its toggles.
2. Presses Home and waits until `dumpsys activity` reports every Activity of the task as stopped **with saved state**. Killing earlier tests a cold start instead.
3. Kills every process of the app: `run-as <pkg> kill -9` on debuggable builds, `am kill` otherwise.
4. Waits until the processes are gone and checks that the task survived.
5. Brings the task back with its root Intent, exactly like tapping the launcher icon.
6. Fails when the app crashed while restoring, a different Activity or task came back, or typed text or toggles changed. If the process ID did not change, the kill cannot be proven and the check stops with exit code 2 instead of passing.

## Install

Requires JDK 17 and `adb` (found through `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `PATH`).

Download `pdcheck-<version>.zip` from [Releases](https://github.com/byfedin/process-death-check/releases), unzip it and run `bin/pdcheck`. To build from source:

```bash
./gradlew :cli:installDist
```

```bash
cli/build/install/pdcheck/bin/pdcheck --help
```

## Usage

Open the screen you want to check on a device or emulator, fill in a form if you like, then:

```bash
pdcheck kill -p com.example.app
```

| Option | Default | Meaning |
| --- | --- | --- |
| `-p, --package` | required | Application ID |
| `-s, --serial` | `ANDROID_SERIAL`, else the only device | Device serial when several are attached |
| `--launch` | off | Force-stop and start the launcher Activity first |
| `--method` | `auto` | `run-as` (debuggable builds), `am-kill` (any build) or `auto` |
| `--no-ui-compare` | off | Only check crashes, screen and process |
| `-o, --output` | `pdcheck-reports` | Where reports and screenshots go |
| `--restore-timeout` | `20000` | Milliseconds to wait for the app to come back |

Exit codes: `0` passed, `1` a problem was found, `2` the check could not run (no device, app not in the foreground, process would not die, restored window unreadable). A sweep exits with `2` when it could not check a single screen.

### Sweep: every screen, no script

`sweep` force-stops and starts the app, taps through the screens it can reach and runs the check on each one. A screen is checked when it first appears and again after a toggle on it changed, because untouched screens rarely lose anything. Crashes caused by the taps themselves are reported too.

```text
$ pdcheck sweep -p dev.byfedin.pdcheck.sample
PASS MainActivity: pdcheck sample (11697 -> 11817)
PASS SavedFormActivity: Saved form (11817 -> 12004)
PASS SavedFormActivity: Saved form (after input) (12004 -> 12186)
FAIL SessionActivity: Signed in as demo
  - Crash while restoring: kotlin.UninitializedPropertyAccessException: lateinit property user has not been initialized
PASS ForgetfulFormActivity: Forgetful form (12467 -> 12626)
FAIL ForgetfulFormActivity: Forgetful form (after input)
  - UI state lost after process death (1)
      toggle:Urgent: "checked" -> "unchecked"
6 checks on 4 screens after 7 actions: 2 failed
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--max-checks` | `20` | Stop after this many screen checks |
| `--max-actions` | `60` | Stop after this many taps and Back presses |
| `--seed` | `1` | Tap order; the same seed repeats the same path |

`--package`, `--serial`, `--method`, `--no-ui-compare` and `--output` work as in `kill`.

The sweep taps clickable elements of your app only and never types. Screens are told apart by the foreground Activity and the labels of their clickable elements, so in a single-Activity Compose app each destination counts as its own screen. Run it on a test account: it presses whatever buttons it finds.

### Gradle plugin

The plugin adds `pdcheckSweep<Variant>` to Android application modules. It installs the variant, finds `adb` through the Android SDK and writes reports to `build/reports/pdcheck/<variant>/`.

```kotlin
plugins {
    id("com.android.application")
    id("dev.byfedin.pdcheck") version "0.1.0"
}

pdcheck {
    maxChecks = 30
    failOnFindings = true
}
```

```bash
./gradlew :app:pdcheckSweepDebug
```

Version 0.1.0 is waiting for review on the Gradle Plugin Portal. Until it shows up there, clone this repository and add it with `includeBuild("path/to/process-death-check")` in the `pluginManagement` block of `settings.gradle.kts`, as `sample/` does, and drop the version from the `plugins` block.

### Reports

Every run writes to the output directory:

- `report.json` with the findings, killed and restored PIDs, and timing;
- `TEST-process-death-check.xml` in JUnit format, so CI shows a failed check like a failed test;
- `before.png` and `after.png`, and the `dumpsys` output from just before the kill.

### In CI

Run it after your UI tests have put the app on a screen, or on its own with `--launch`:

```yaml
- uses: ReactiveCircus/android-emulator-runner@v2
  with:
    api-level: 36
    script: |
      ./gradlew :app:installDebug
      pdcheck kill -p com.example.app --launch -o build/pdcheck
```

## What counts as state

Text in editable fields (Views and Compose text fields), the checked state of checkboxes, switches and other toggles, and selected tabs or chips. Fields are matched by resource ID, then by position. Toggles are matched by resource ID, then by their label, then by position. Password fields are skipped.

Scroll positions, loaded lists and anything the app reloads from the network are out of scope on purpose: reloading them after process death is legitimate. If you keep a list in `SavedStateHandle`, assert it in your own test.

## Limits

- `run-as` works only for debuggable builds. `am kill` works for release builds but only kills a process the system already treats as cached.
- Apps with several processes are supported; every `pkg:*` process is killed.
- In a single-Activity app, `kill` compares the Activity and task, not the Compose destination. Coming back to the start destination is caught only when a field or toggle on the lost screen had a value. A sweep labels destinations separately, but each check has the same limit.
- Screens drawn without accessibility nodes (games, custom canvases, most WebViews) can be checked for crashes, but not for lost input.
- `kill` covers one screen. `sweep` reaches only what taps can reach: screens behind sign-in, typed input or deep links need `kill` from your own setup.

## Try it on the sample

`sample/` is an app whose screens are correct or broken on purpose: a form that keeps its state with `rememberSaveable`, one that uses `remember`, and a screen that reads a `lateinit` singleton set by the previous screen.

```bash
./gradlew -p sample :app:installDebug
```

```bash
./gradlew :e2e:test -Pe2e
```

The `e2e` tests open each screen, run the check and assert the expected result, then run a sweep and assert that it finds both broken screens. CI runs them on API 26 and API 36 emulators.

## Development

```bash
./gradlew spotlessApply build
```

`core` holds the parsers, the check and the sweep, and has no dependencies. `cli` and `gradle-plugin` are thin wrappers around it. Its unit tests use real `dumpsys`, `ps`, `logcat` and `uiautomator` output from API 26 and 36, and a scripted device with a virtual clock, so they run in a second and never sleep.

## License

MIT
