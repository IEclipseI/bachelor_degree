package tests

import kset.NonBlockingFriendlySkipListSet
import mine.KSkipListConcurrentGeneric
import mine.util.Statistic
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class PerformanceTests(
    private val threadCounts: List<Int>,
    private val updateRates: List<Double>,
    private val valuesLists: List<IntRange>,
    private val kArraySizes: List<Int> = listOf(32),
    private val seconds: Int = 1,
    private val executionsNumber: Int = 1
) {
    private val mainRandom = Random(System.currentTimeMillis())


    private fun struct(k: Int): Struct {
        return Struct("KSkipListConcurrent($k)") { KSkipListConcurrentGeneric(k) }
    }

    fun run() {
        val structuresToTest = kArraySizes.map { struct(it) } + listOf(
            Struct("ConcurrentSkipListSet") { ConcurrentSkipListSet() },
            Struct("NonBlockingFriendlySkipListSet") { NonBlockingFriendlySkipListSet() }
        )
        for (values in valuesLists) {
            println(values.last)
            val initialState = (0 until executionsNumber).map {
                val initialState = values.shuffled(mainRandom).take(values.last / 2)
                initialState to mainRandom.nextInt()
            }
            for ((t, threads) in threadCounts.withIndex()) {
                for ((index, insertRate) in updateRates.withIndex()) {
                    println("Threads: $threads, update rate: $insertRate")
                    for (structure in structuresToTest) {
                        val results = ArrayList<Long>()
                        val heights = ArrayList<Int>()
                        val statistics = CopyOnWriteArrayList<Statistic>()
                        initialState.map { (a, seed) ->
                            val list = structure.construct()
                            list.addAll(a)
                            val localRandom = Random(seed)
                            val startTime = Instant.now()
                            val finishTime = startTime.plusSeconds(seconds.toLong())
                            val operationsDone = (0 until threads).map {
                                Random(localRandom.nextInt())
                            }.map { rnd ->
                                var opsDone = LongArray(1) { 0L }
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
                                    statistics.add(KSkipListConcurrentGeneric.stat.get())
                                }
                                thread to opsDone
                            }.map {
                                it.component1().start()
                                it
                            }.map {
                                it.component1().join()
                                it.component2()[0]
                            }.sum()
                            heights.add(if (list is KSkipListConcurrentGeneric) list.maxLevel.get() else 0)
                            results.add(operationsDone)
                            System.gc()
                            if (list is NonBlockingFriendlySkipListSet)
                                list.stopMaintenance()
                        }
                        results.sort()
                        val average = results.average().div(seconds)

                        println(String.format("%-31s %12.0f", structure.name, average))
//                        println(String.format("Optimistic/pessimistic add %.4f",
//                            statistics.map { it.successfulOptimisticAdds / it.failedOptimisticAdds.toDouble() }
//                                .average())
//                        )
//                        println(String.format("Optimistic/pessimistic remove %.4f",
//                            statistics.map { it.successfulOptimisticRemoves / it.failedOptimisticRemoves.toDouble() }
//                                .average())
//                        )
//                        println(String.format("Find first not deleted average range %.9f",
//                            statistics.map { it.deletedNodesTraversed.toDouble() / it.findNotDeletedCalls }
//                                .average())
//                        )
//                        println(String.format("Move forward average range %.9f",
//                            statistics.map { it.nodesMovedForward.toDouble() / it.moveForwardRequests }
//                                .average())
//                        )

                    }
                    if (index != updateRates.size - 1 || t != threadCounts.size - 1)
                        println("–".repeat(120))
                    println()
                }
            }
            println("#".repeat(120))
            println("#".repeat(120))
            println("#".repeat(120))
            println()
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