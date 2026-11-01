package dev.byfedin.pdcheck.sample

/** Lives in memory only, so it is empty in a restored process. */
object Session {
    lateinit var user: String
}
