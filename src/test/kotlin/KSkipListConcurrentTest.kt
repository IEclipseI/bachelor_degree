import com.google.common.base.Stopwatch
import mine.KSkipListConcurrentGeneric
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.assertEquals

private val <E> ConcurrentSkipListSet<E>.opsDone: Int
    get() {
        return 0
    }

class KSkipListConcurrentTest {
    private val random = Random(System.currentTimeMillis())

    companion object {
        const val debug = false
//        const val debug = true
    }

    private val testedOperations = setOf(
        Operation.ADD,
        Operation.REMOVE,
        Operation.CONTAINS
    )

    @RepeatedTest(100)
    fun crossThreadValidation() {
        val threadCount = Runtime.getRuntime().availableProcessors()
        val insertRates = listOf(0.5, 0.55, 0.65, 0.75, 0.85, 0.95)
        val values = 1..100000
        val operationsPerThread = 10000
        val k = 32
        for (insertRate in insertRates) {
//            val skipList = KSkipListConcurrentV1(k)
//            val skipList = ConcurrentSkipListSet<Int>()
//            val skipList = KSkipListConcurrentV1Generic<Int>(k)
            val skipList = KSkipListConcurrentGeneric<Int>(k)
            check((1..threadCount).map {
                val operationsResult = values.map { value ->
                    value to testedOperations
                        .map { it to OperationResult(0, 0) }
                        .toMap()
                }.toMap()
                operationsResult to Thread {
                    val random = Random(System.currentTimeMillis())
                    repeat(operationsPerThread) {
                        val operation = chooseOperation(insertRate)
                        val element = values.random(random)
                        val function = operations[operation]!!
                        val callResult = function.invoke(skipList, element)
                        val operationResult = operationsResult[element]!![operation]!!
                        if (callResult)
                            operationResult.successful += 1
                        else {
                            operationResult.failed += 1
                        }
                    }
                }
            }.map {
                it.second.start()
                it
            }.map {
                it.second.join()
                it.first
            }.flatMap {
                it.asSequence()
            }.groupBy(
                { it.key }, { it.value }
            ).map {
                it.key to it.value.flatMap {
                    it.asSequence()
                }.groupBy(
                    { it.key }, { it.value }
                ).map {
                    it.key to it.value.fold(OperationResult(0, 0)) { acc, operationResult ->
                        OperationResult(
                            acc.successful + operationResult.successful,
                            acc.failed + operationResult.failed
                        )
                    }
                }.toMap()
            }.map { (value, ops) ->
                val (removeSuccess, removeFails) = ops[Operation.REMOVE]!!
                val (addSuccess, addFails) = ops[Operation.ADD]!!
                check((addSuccess - removeSuccess) in 0..1)
                if (addSuccess > removeSuccess) {
                    check(skipList.contains(value))
                } else {
                    check(!skipList.contains(value))
                }
                addSuccess - removeSuccess;
            }.sum() == skipList.size)
//            println("fullfilment ${skipList.fullfilment()}")
        }
    }

    data class OperationResult(var successful: Int, var failed: Int)

    @RepeatedTest(100)
    fun allOperations() {
        val repeatTestCase = 100
        val insertRates = listOf(0.5, 0.55, 0.65, 0.75, 0.85, 0.95)
        val valuesList = listOf(1..20, 1..100, 1..1000, 1..10_000, 1..100_000)
//        val valuesList = listOf(1..1000, 1..10_000, 1..100_000)
//        val valuesList = listOf(1..2)
        val ops = 10_00
        var cur = 0
        val threads = 2..Runtime.getRuntime().availableProcessors()
//        val threads = 1..1
//        val threads = 2..2
        val total = insertRates.size * valuesList.size * repeatTestCase * threads.toList().size

        for (threadsCount in threads) {
            println("threadsCount = $threadsCount")
            val createStarted = Stopwatch.createStarted()
            for (values in valuesList) {
                val threadValues = (0 until threadsCount).map { ArrayList<Int>() }
                values.toList().subList(0, threadsCount).map { threadValues[it - 1].add(it) }
                values.toList().subList(threadsCount, values.last)
                    .map { threadValues[random.nextInt(threadsCount)].add(it) }
                for (insertRate in insertRates) {
                    repeat(repeatTestCase) {
                        cur++
                        if (cur % (total / 100L) == 0L)
                            println("${cur / (total / 100)}%")
                        val anyProblem = AtomicReference<Throwable?>(null)
//                        val skipList = KSkipListConcurrentV1(4)
//                        val skipList = KSkipListConcurrentV1Generic<Int>(4)
                        val skipList = KSkipListConcurrentGeneric<Int>(4)
//                        val skipList = ConcurrentSkipListSet<Int>()
                        val finished = AtomicBoolean(false)
                        (0 until threadsCount).map { threadId ->
                            Thread {
                                val checkSet = HashSet<Int>()
                                repeat(ops) curIteration@{
                                    var opLog: Operation? = null
                                    var elementLog: Int? = null
                                    try {
                                        val operation = chooseOperation(insertRate)
                                        opLog = operation
                                        val element = threadValues[threadId].random(random)
                                        elementLog = element

                                        val op = operations[operation]!!
//
                                        if (!testedOperations.contains(operation)) {
                                            return@curIteration
                                        }
                                        op.let { func ->
                                            assertEquals(
                                                func.invoke(checkSet, element),
                                                func.invoke(skipList, element)
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        anyProblem.compareAndSet(null, e)
                                        println(
                                            """
                                            |checkSet= $checkSet
                                            |skipList=
                                            |${skipList}
                                            |operation = $opLog
                                            |element = $elementLog
                                            |skipList.toSet() = ${skipList.toSet()}
                                            """.trimMargin()
                                        )
                                        println("—".repeat(200))
                                        throw e
                                    } finally {
//                            debug {
//                                println(
//                                    """
//                            |checkSet= $checkSet
//                            |skipList=
//                            |${skipList}
//                            |skipList.toSet() = ${skipList.toSet()}
//                        """.trimMargin()
//                                )
//                                println("—".repeat(200))
//                            }
                                    }
                                }
                            }
                        }.map {
                            it.start()
                            it
                        }.map { it.join() }
                        finished.set(true)
                        anyProblem.get()?.let { throw it }
                    }
                }
            }
            println(createStarted.elapsed(TimeUnit.MILLISECONDS))
        }
    }

    private fun chooseOperation(insertRate: Double): Operation {
        return when (OperationSelection.values().random(random)) {
            OperationSelection.ADD_OR_REMOVE ->
                if (random.nextDouble() < insertRate)
                    Operation.ADD
                else
                    Operation.REMOVE
//                    Operation.ADD
            OperationSelection.CONTAINS -> Operation.CONTAINS
        }
    }

    private fun debug(f: Runnable) {
        if (debug)
            f.run()
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

//    @Test
//    fun obSize() {
//        println(ClassLayout.parseInstance((1..10000).toSet()))
//    }
}

