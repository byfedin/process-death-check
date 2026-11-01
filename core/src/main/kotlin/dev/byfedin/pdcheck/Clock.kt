package dev.byfedin.pdcheck

/** Time source for polling. Tests use a virtual clock so they never sleep. */
public interface Clock {
    public fun nowMillis(): Long

    public fun sleep(millis: Long)

    public object System : Clock {
        override fun nowMillis(): Long = java.lang.System.nanoTime() / 1_000_000

        override fun sleep(millis: Long): Unit = Thread.sleep(millis)
    }
}

internal class TimeoutException(val description: String) : RuntimeException(description)

/** Polls [probe] until it returns non-null or [timeoutMillis] passes. */
internal fun <T : Any> Clock.await(
    description: String,
    timeoutMillis: Long,
    intervalMillis: Long = 250,
    probe: () -> T?,
): T {
    val deadline = nowMillis() + timeoutMillis
    while (true) {
        probe()?.let {
            return it
        }
        if (nowMillis() >= deadline) throw TimeoutException(description)
        sleep(intervalMillis)
    }
}
