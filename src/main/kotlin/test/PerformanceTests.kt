package test

import tests.PerformanceTests
import java.time.Duration

fun main() {
    PerformanceTests(
        listOf(1, 10, 18, 22, 26, 28, 30, 32), //8
        listOf(0.1, 0.5, 1.0), //5
        listOf(1..100_000, 1..500_000, 1..1_500_000, 1..5_000_000), //4
        kArraySizes = listOf(32), //7
        seconds = Duration.ofMinutes(2).toSeconds().toInt(),
        executionsNumber = 1,
        onlyKArray = false).run()
    println("Finished")
}
