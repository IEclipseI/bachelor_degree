package test

import tests.PerformanceTests
import java.time.Duration

fun main() {
    PerformanceTests(
        listOf(32), //9
        listOf(1.0), //5
        listOf(1..1_500_000, 1..5_000_000), //4
        kArraySizes = listOf(36), //7
        seconds = Duration.ofMinutes(5).toSeconds().toInt(),
        executionsNumber = 1).run()
    println("Finished")
}
