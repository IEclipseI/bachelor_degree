package test

import tests.PerformanceTests

fun main() {
    PerformanceTests(
        listOf(4, 8),
        listOf(0.1, 0.2),
        listOf(1..1000, 1..100_000),
        seconds = 1).run()
}
