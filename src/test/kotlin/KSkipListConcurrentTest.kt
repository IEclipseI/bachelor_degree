import mine.KSkipListConcurrentV1
import mine.KSkipListV1
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.ClassLayout
import java.lang.instrument.Instrumentation
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KSkipListConcurrentTest {
    val random = Random(System.currentTimeMillis())

    companion object {
//        const val debug = false
        const val debug = true
    }
//    val debug = true

    //    @RepeatedTest(10)
    @RepeatedTest(10)
    fun singleThreadInsert() {
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
                            op.let { (first, second) ->
                                when (operation) {
                                    Operation.ADD -> assertEquals(
                                        second.invoke(checkSet, element),
                                        first.invoke(skipList, element)
                                    )
                                    Operation.REMOVE -> assertEquals(
                                        second.invoke(checkSet, element),
                                        first.invoke(skipList, element)
                                    )
                                    Operation.CONTAINS -> assertEquals(
                                        second.invoke(checkSet, element),
                                        first.invoke(skipList, element)
                                    )
                                }
//                                assertEquals(checkSet.size, skipList.size())
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


    @RepeatedTest(100)
    fun concurrentInsert() {
        val range = 1..1_000_000
//        val threads = 2..Runtime.getRuntime().availableProcessors() * 2
//        val threads = 2..2
        val threads = 8..8
//        val threads = 1..1

        val exc: AtomicReference<Throwable?> = AtomicReference(null)

        for (threadCount in threads) {
            val list = KSkipListConcurrentV1(64)
//            val list = ConcurrentSkipListSet<Int>()
            println("Threads $threadCount")
            val intRange = range.toList().shuffled(random)
            val values = (0 until threadCount).map { ArrayList<Int>() }
            intRange.map { values[random.nextInt(threadCount)].add(it) }
            val finished = AtomicBoolean(false)
            debug {
                Thread {
//                    var prev = 0
                    while (true) {
                        Thread.sleep(2000)
//                        val cur = list.opsDone.get()
//                        if (cur == prev && !finished.get()) {
//                            println(list)
//                            break
//                        }
//                        prev = cur
                    }
//                    exc.get()?.let { throw it }
                }.start()
            }
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
            finished.set(true)
            assertEquals(
                range.toList(), list.toList().sorted()
            )
            check(range.all { list.contains(it) })
        }
    }


    private fun chooseOperation(insertRate: Double): Operation {
        return when (OperationSelection.values().random(random)) {
            OperationSelection.ADD_OR_REMOVE ->
                if (random.nextDouble() < insertRate)
                    Operation.ADD
                else
                    Operation.REMOVE
            OperationSelection.CONTAINS -> Operation.CONTAINS
        }
    }

    private fun debug(f: Runnable) {
        if (debug)
            f.run()
    }

    private val operations = mapOf(
        Operation.ADD to (KSkipListConcurrentV1::add as (KSkipListConcurrentV1, Int) -> Boolean to HashSet<Int>::add as (HashSet<Int>, Int) -> Boolean),
        Operation.REMOVE to (KSkipListConcurrentV1::remove to HashSet<Int>::remove),
        Operation.CONTAINS to (KSkipListConcurrentV1::contains to HashSet<Int>::contains)
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

    @Test
    fun obSize() {
        println(ClassLayout.parseInstance((1..10000).toSet()))
    }
}

