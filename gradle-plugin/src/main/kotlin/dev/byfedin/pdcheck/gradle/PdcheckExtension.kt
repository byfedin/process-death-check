package dev.byfedin.pdcheck.gradle

import org.gradle.api.provider.Property

/** `pdcheck { }` block in the app module. Every value has a default. */
abstract class PdcheckExtension {
    /** Device serial; needed only when several devices are attached. */
    abstract val serial: Property<String>

    /** Stop after this many screen checks. Default 20. */
    abstract val maxChecks: Property<Int>

    /** Stop after this many taps. Default 60. */
    abstract val maxActions: Property<Int>

    /** Seed for the tap order. Default 1. */
    abstract val seed: Property<Int>

    /** Compare text fields and toggles before and after. Default true. */
    abstract val compareUi: Property<Boolean>

    /** Fail the build when a check finds a problem. Default true. */
    abstract val failOnFindings: Property<Boolean>

    init {
        maxChecks.convention(20)
        maxActions.convention(60)
        seed.convention(1)
        compareUi.convention(true)
        failOnFindings.convention(true)
    }
}
