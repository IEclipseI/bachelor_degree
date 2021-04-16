package test

import mine.KSkipListConcurrentGeneric
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.random.Random

fun main() {
    PerformanceTests().performance()
}

class PerformanceTests {
    //    val threadCounts = listOf(1, 2, 4, 8, 16, 32, 64)
//
    val threadCounts = listOf(8)
    val mainRandom = Random(System.currentTimeMillis())

    //    val insertRates = listOf(0.2, 0.5, 1.0)
    val insertRates = listOf(1.0)

    fun performance() {
        val executionsNumber = 10
        val values = 1..100_000
        val initialState = (0 until executionsNumber).map {
            val initialState = values.shuffled(mainRandom).take(values.last / 2)
            initialState to mainRandom.nextInt()
        }

        val structuresToTest = listOf(
            Struct("KSkipListConcurrent(2)") { KSkipListConcurrentGeneric(2) },
            Struct("KSkipListConcurrent(4)") { KSkipListConcurrentGeneric(4) },
            Struct("KSkipListConcurrent(8)") { KSkipListConcurrentGeneric(8) },
            Struct("ConcurrentSkipListSet") { ConcurrentSkipListSet() },
            Struct("KSkipListConcurrent(16)") { KSkipListConcurrentGeneric(16) },
            Struct("KSkipListConcurrent(32)") { KSkipListConcurrentGeneric(32) },
            Struct("KSkipListConcurrent(64)") { KSkipListConcurrentGeneric(64) },
            Struct("KSkipListConcurrent(64)") { KSkipListConcurrentGeneric(128) }
        )
        for (threads in threadCounts) {
            for (insertRate in insertRates) {
                println("Threads: $threads, insert rate: $insertRate")
                for (structure in structuresToTest) {
                    val results = ArrayList<Int>()
                    initialState.map { (a, seed) ->
                        val list = structure.construct()
                        list.addAll(a)
                        val startTime = Instant.now()
                        val finishTime = startTime.plusSeconds(1)
                        val localRandom = Random(seed)
                        val operationsDone = (0 until threads).map {
                            Random(localRandom.nextInt())
                        }.map { rnd ->
                            var opsDone = IntArray(1) { 0 }
                            val thread = Thread {
                                var run = true
                                while (run) {
                                    repeat(100) {
                                        val op = chooseOperation(insertRate, rnd)
                                        val v = values.random(rnd)
                                        val function = operations[op]!!
                                        function(list, v)
                                        opsDone[0] = opsDone[0] + 1
                                    }
                                    run = Instant.now().isBefore(finishTime)
                                }
                            }
                            thread to opsDone
                        }.map {
                            it.component1().start()
                            it
                        }.map {
                            it.component1().join()
                            it.component2()[0]
                        }.sum()
                        results.add(operationsDone)
                        System.gc()
                    }
                    results.sort()
                    val average = results.drop(3).dropLast(3).average()
                    println(structure.name + " " + average)

                }
                println("–".repeat(120))
                println()
            }
        }
    }

    private fun chooseOperation(insertRate: Double, random: Random): Operation {
        return when (if (random.nextDouble() < insertRate) OperationSelection.ADD_OR_REMOVE else OperationSelection.CONTAINS) {
            OperationSelection.ADD_OR_REMOVE ->
                if (random.nextDouble() < 0.5)
                    Operation.ADD
                else
                    Operation.REMOVE
            OperationSelection.CONTAINS -> Operation.CONTAINS
        }
    }

    private val operations: Map<Operation, (MutableSet<Int>, Int) -> Boolean> = mapOf(
        Operation.ADD to MutableSet<Int>::add,
        Operation.REMOVE to MutableSet<Int>::remove,
        Operation.CONTAINS to MutableSet<Int>::contains
    )

    //
    enum class OperationSelection {
        ADD_OR_REMOVE,
        CONTAINS
    }

    enum class Operation {
        ADD,
        REMOVE,
        CONTAINS
    }

    data class Struct(val name: String, val construct: () -> MutableSet<Int>)
}