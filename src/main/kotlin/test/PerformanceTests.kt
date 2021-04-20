package test

import tests.PerformanceTests

fun main() {
    PerformanceTests(
        listOf(32),
        listOf(0.1, 0.8),
        listOf(1..1_000_000),
        seconds = 1).run()
}
