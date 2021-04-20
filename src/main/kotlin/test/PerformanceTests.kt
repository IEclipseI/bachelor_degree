package test

import tests.PerformanceTests

fun main() {
    PerformanceTests(
        listOf(1, 2, 4, 8, 12, 16, 22, 26, 32), //9
        listOf(0.2, 0.35, 0.5, 0.75, 1.0), //5
        listOf(1..100_000, 1..500_000, 1..1_500_000, 1..5_000_000), //4
        kArraySizes = (16..40 step 4).toList(), //7
        seconds = 20,
        executionsNumber = 1).run()
    println("Finished")
}
