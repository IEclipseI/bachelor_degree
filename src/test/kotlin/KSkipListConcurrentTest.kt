import com.google.common.base.Stopwatch
import mine.KSkipListConcurrentV1
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val <E> ConcurrentSkipListSet<E>.opsDone: Int
    get() {
        return 0
    }

class KSkipListConcurrentTest {
    val random = Random(System.currentTimeMillis())

    companion object {
        const val debug = false
//        const val debug = true
    }

    @RepeatedTest(100)
    fun allOperations() {
        val repeatTestCase = 100
        val insertRates = listOf(0.55, 0.65, 0.75, 0.85, 0.95)
        val valuesList = listOf(1..20, 1..100, 1..1000, 1..10_000, 1..100_000)
//        val valuesList = listOf(1..1000, 1..10_000, 1..100_000)
//        val valuesList = listOf(1..2)
        val ops = 10_00
        var cur = 0
        val threads = 2..Runtime.getRuntime().availableProcessors() * 2
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
                        val skipList = KSkipListConcurrentV1(4)
//                        val skipList = ConcurrentSkipListSet<Int>()
                        val finished = AtomicBoolean(false)
                        debug { // check that structure not handling
                            Thread {
                                var prev = 0
                                while (true) {
                                    Thread.sleep(3000)
                                    val cur = skipList.opsDone
                                    if (cur == prev && !finished.get()) {
                                        println(skipList)
                                        break
                                    }
                                    prev = cur
                                }
//                                exc.get()?.let { throw it }
                            }.start()
                        }
                        (0 until threadsCount).map { threadId ->
                            Thread {
                                val checkSet = HashSet<Int>()
                                repeat(ops) {
                                    var opLog: Operation? = null
                                    var elementLog: Int? = null
                                    try {
                                        val operation = chooseOperation(insertRate)
                                        opLog = operation
                                        val element = threadValues[threadId].random(random)
                                        elementLog = element

                                        val op = operations[operation]!!
//                                        if (operation == Operation.REMOVE) {
//                                            return@repeat
//                                        }
                                        op.let { func ->
                                            assertEquals(
                                                func.invoke(checkSet, element),
                                                func.invoke(skipList, element)
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        if (opLog == Operation.CONTAINS) {
                                            return@repeat
                                        }
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

    @RepeatedTest(100)
    fun concurrentInsert() {
        val range = 1..1_000_000
        val threads = 2..Runtime.getRuntime().availableProcessors() * 2
//        val threads = 2..2
//        val threads = 8..8
//        val threads = 1..1


        for (threadCount in threads) {
            val exc: AtomicReference<Throwable?> = AtomicReference(null)
//            val list = KSkipListConcurrentV1(64)
            val list = ConcurrentSkipListSet<Int>()
            println("Threads $threadCount")
            val intRange = range.toList().shuffled(random)
            val values = (0 until threadCount).map { ArrayList<Int>() }
            intRange.map { values[random.nextInt(threadCount)].add(it) }
            val finished = AtomicBoolean(false)
//            debug { // check that structure not handling
//                Thread {
//                    var prev = 0
//                    while (true) {
//                        Thread.sleep(5000)
//                        val cur = list.opsDone.get()
//                        if (cur == prev && !finished.get()) {
//                            println("ERROR HANDLING")
//                            break
//                        }
//                        prev = cur
//                    }
//                    exc.get()?.let { throw it }
//                }.start()
//            }
            (0 until threadCount)
                .map { threadId ->
                    Thread({
                        try {
//                            val alreadyAdded = HashSet<Int>()
                            val curValues = values[threadId]
                            for (value in curValues) {
                                check(list.add(value))
//                                alreadyAdded.add(value)
                            }
//                            check(alreadyAdded.all { list.contains(it) })
                        } catch (e: Throwable) {
                            exc.compareAndSet(null, e)
                        }
                    }, "Thread-$threadId")
                }.map {
                    it.start()
                    it
                }.map { it.join() }
            if (exc.get() != null) {
                throw exc.get()!!
            }
            finished.set(true)
            assertEquals(
                range.toList(), list.toList().sorted()
            )
            check(range.all { list.contains(it) })
        }
    }


    @RepeatedTest(10)
    fun singleThreadOperations() {
        val repeatTestCase = 100
        val insertRates = listOf(0.55, 0.65, 0.75, 0.85, 0.95)
        val valuesList = listOf(1..2, 1..5, 1..10, 1..100, 1..1000, 1..10_000)
        val ops = 100_000
        var cur = 0
        val total = ops.toLong() * insertRates.size * valuesList.size * repeatTestCase
        for (insertRate in insertRates) {
            for (values in valuesList) {
                repeat(repeatTestCase) {
                    val checkSet = HashSet<Int>()
                    val skipList = KSkipListConcurrentV1(8)
//                    val skipList = ConcurrentSkipListSet<Int>()
                    assertTrue { skipList.toSet() == checkSet }
                    repeat(ops) {
                        var op_log: Operation? = null
                        var element_log: Int? = null
                        try {
                            cur++
                            if (cur % (total / 100) == 0L)
                                println("${cur / (total / 100)}%")

                            val operation = chooseOperation(insertRate)
                            op_log = operation
                            val element = values.random(random)
                            element_log = element
                            debug {
                                println(
                                    """
                            |checkSet= $checkSet
                            |skipList=
                            |${skipList}
                            |operation = $operation
                            |element = $element
                        """.trimMargin()
                                )
                            }

                            val op = operations[operation]!!
                            op.let { func ->
                                assertEquals(
                                    func.invoke(checkSet, element),
                                    func.invoke(skipList, element)
                                )
                                assertEquals(checkSet.size, skipList.size)
                            }
                        } catch (e: Throwable) {
                            println(
                                """
                            |checkSet= $checkSet
                            |skipList=
                            |${skipList}
                            |operation = $op_log
                            |element = $element_log
                        """.trimMargin()
                            )


                            println(
                                """
                            |checkSet= $checkSet
                            |skipList=
                            |${skipList}
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
                    assertTrue { skipList.toSet() == checkSet }
                    assertTrue { skipList.toList().sorted() == checkSet.toList().sorted() }
                }
            }
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

