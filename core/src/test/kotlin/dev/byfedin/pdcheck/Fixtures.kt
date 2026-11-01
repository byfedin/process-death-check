package dev.byfedin.pdcheck

internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "Missing fixture $name" }
        .readText()
