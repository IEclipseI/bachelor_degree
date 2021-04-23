package test

import tests.PerformanceTests
import java.time.Duration

fun main() {
    PerformanceTests(
        listOf(32), //8
        listOf(1.0), //5
        listOf(1..500_000), //4
        kArraySizes = listOf(32), //7
        seconds = Duration.ofMinutes(2).toSeconds().toInt(),
        executionsNumber = 1,
        onlyKArray = false).run()
    println("Finished")
}
