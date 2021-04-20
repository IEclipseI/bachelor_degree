import mine.KSkipListConcurrentGeneric
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.random.Random

class PerformanceTests {
    val mainRandom = Random(System.currentTimeMillis())

    //        val threadCounts = listOf(8, 12)
    val threadCounts = listOf(12)
//    val threadCounts = listOf(12)
//    val threadCounts = listOf(12)

    val insertRates = listOf(0.1, 0.2, 0.35, 0.5, 1.0)
//    val insertRates = listOf(0.0)
//    val insertRates = listOf(1.0)

    @Test
    fun performance() {
        val executionsNumber = 1
        val values = 1..100_000;
        val initialState = (0 until executionsNumber).map {
            val initialState = values.shuffled(mainRandom).take(values.last / 2)
            initialState to mainRandom.nextInt()
        }
        val seconds = 15L
        println(values.last)
        val structuresToTest = listOf(
//            Struct("KSkipListConcurrent(2)") { KSkipListConcurrentGeneric(2) },
//            Struct("KSkipListConcurrent(4)") { KSkipListConcurrentGeneric(4) },
            Struct("KSkipListConcurrent(8)") { KSkipListConcurrentGeneric(8) },
            Struct("KSkipListConcurrent(16)") { KSkipListConcurrentGeneric(16) },
            Struct("KSkipListConcurrent(24)") { KSkipListConcurrentGeneric(24) },
            Struct("KSkipListConcurrent(32)") { KSkipListConcurrentGeneric(32) },
            Struct("KSkipListConcurrent(40)") { KSkipListConcurrentGeneric(40) },
//            Struct("KSkipListConcurrent(48)") { KSkipListConcurrentGeneric(48) },
//            Struct("KSkipListConcurrent(56)") { KSkipListConcurrentGeneric(56) },
//            Struct("KSkipListConcurrent(64)") { KSkipListConcurrentGeneric(64) },
//            Struct("KSkipListConcurrent(68)") { KSkipListConcurrentGeneric(68) },
//            Struct("KSkipListConcurrent(72)") { KSkipListConcurrentGeneric(72) },
//            Struct("KSkipListConcurrent(80)") { KSkipListConcurrentGeneric(80) },
//            Struct("KSkipListConcurrent(88)") { KSkipListConcurrentGeneric(88) },
//            Struct("KSkipListConcurrent(96)") { KSkipListConcurrentGeneric(96) },
//            Struct("KSkipListConcurrent(128)") { KSkipListConcurrentGeneric(128) },
//            Struct("KSkipListConcurrent(144)") { KSkipListConcurrentGeneric(144) },
//            Struct("KSkipListConcurrent(160)") { KSkipListConcurrentGeneric(160) },
//            Struct("KSkipListConcurrent(176)") { KSkipListConcurrentGeneric(176) },
//            Struct("KSkipListConcurrent(182)") { KSkipListConcurrentGeneric(182) },
//            Struct("KSkipListConcurrent(198)") { KSkipListConcurrentGeneric(198) },
//            Struct("KSkipListConcurrent(214)") { KSkipListConcurrentGeneric(214) },
//            Struct("KSkipListConcurrent(230)") { KSkipListConcurrentGeneric(230) },
//            Struct("KSkipListConcurrent(246)") { KSkipListConcurrentGeneric(246) },
//            Struct("KSkipListConcurrent(262)") { KSkipListConcurrentGeneric(262) },
            Struct("ConcurrentSkipListSet") { ConcurrentSkipListSet() },
//            Struct("NonBlockingFriendlySkipListSet") { NonBlockingFriendlySkipListSet() },
//            Struct("KSkipListConcurrent(64)") { KSkipListConcurrentGeneric(128) }
        )
        for (threads in threadCounts) {
            for (insertRate in insertRates) {
                println("Threads: $threads, insert rate: $insertRate")
                for (structure in structuresToTest) {
                    val results = ArrayList<Int>()
                    val heights = ArrayList<Int>()
                    initialState.map { (a, seed) ->
                        val list = structure.construct()
                        list.addAll(a)
                        val startTime = Instant.now()
                        val finishTime = startTime.plusSeconds(seconds)
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
                        heights.add(if (list is KSkipListConcurrentGeneric) list.maxLevel.get() else 0)
                        results.add(operationsDone)
                        System.gc()
                    }
                    results.sort()
//                    val average = results.drop(3).dropLast(3).average().div(seconds)
                    val average = results.average().div(seconds)
                    val high = heights.average()
                    println(String.format("%-24s %12.0f  %.2f", structure.name, average, high))

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