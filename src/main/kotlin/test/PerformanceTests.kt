package test

import tests.PerformanceTests
import java.time.Duration

fun main() {
    PerformanceTests(
        listOf(2, 16, 32, 48, 64, 72, 80), //7
        listOf(0.2, 0.5, 1.0), //3
        listOf(1..10_000, 1..100_000, 1..500_000, 1..1_500_000, 1..5_000_000, 1..10_000_000), //6
        kArraySizes = listOf(32),
//        seconds = Duration.ofMinutes(5).toSeconds().toInt(),
        seconds = Duration.ofSeconds(1).toSeconds().toInt(),
        executionsNumber = 1,
        onlyKArray = false).run()
    println("Finished")
}
