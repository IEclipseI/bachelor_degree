import KSkipListTest.OperationSelection.ADD_OR_REMOVE
import KSkipListTest.OperationSelection.CONTAINS
import mine.Vitalik8
import mine.KSkipList
import mine.KSkipListV1
import org.junit.jupiter.api.RepeatedTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KSkipListTest {
    val random = Random(System.currentTimeMillis())

    val debug = false
//    val debug = true


    @Test
    fun addMany() {
        val n = 3000
        val kSkipList = KSkipList()
//        println(kSkipList.getAll())
        for (i in 1..n) {
            kSkipList.add(i)


//            println(kSkipList.getAll())
            for (j in 1..i) {
                assertTrue(kSkipList.contains(j), "$j")
            }
        }
        for (i in 1..n) {
            assertTrue(kSkipList.contains(i))
        }
    }

    @Test
    fun testVitalikInsert() {
        val structure = Vitalik8()
        repeat(40) {

            structure.add(random.nextInt(15))
            println("$it: $structure")
        }
        println()
        repeat(40) {

            structure.remove(random.nextInt(15))
            println("$it: $structure")
        }
        println()
        repeat(40) {

            structure.add(random.nextInt(15))
            println("$it: $structure")
        }
    }

    @RepeatedTest(10)
    fun deleteInsert() {
        val repeatTestCase = 100
        val insertRates = listOf(0.55, 0.65, 0.75, 0.85, 0.95)
        val valuesList = listOf(1..2, 1..5, 1..10, 1..100, 1..1000, 1..10_000)
        val ops = 10_000
        var cur = 0
        val total = ops.toLong() * insertRates.size * valuesList.size * repeatTestCase
        for (insertRate in insertRates) {
            for (values in valuesList) {
                repeat(repeatTestCase) {
                    val checkSet = HashSet<Int>()
                    val skipList = KSkipListV1(8)
                    assertTrue { skipList.toSet() == checkSet }
                    repeat(ops) {
                        try {
                            cur++
                            if (cur % (total / 100) == 0L)
                                println("${cur / (total / 100)}%")

                            val operation = chooseOperation(insertRate)
                            val element = values.random()
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
                                assertEquals(checkSet.size, skipList.size())
                            }
                        } finally {
                            debug {
                                println(
                                    """
                            |checkSet= $checkSet
                            |skipList=
                            |${skipList}
                            |skipList.toSet() = ${skipList.toSet()}
                        """.trimMargin()
                                )
                                println("—".repeat(200))
                            }
                        }
                    }
                    assertTrue { skipList.toSet() == checkSet }
                }
            }
        }
    }

    private fun chooseOperation(insertRate: Double): Operation {
        return when (OperationSelection.values().random(random)) {
            ADD_OR_REMOVE ->
                if (random.nextDouble() < insertRate)
                    Operation.ADD
                else
                    Operation.REMOVE
            CONTAINS -> Operation.CONTAINS
        }
    }

    private fun debug(f: Runnable) {
        if (debug)
            f.run()
    }

    private val operations = mapOf(
        Operation.ADD to (KSkipListV1::add as (KSkipListV1, Int) -> Boolean to HashSet<Int>::add as (HashSet<Int>, Int) -> Boolean),
        Operation.REMOVE to (KSkipListV1::remove to HashSet<Int>::remove),
        Operation.CONTAINS to (KSkipListV1::contains to HashSet<Int>::contains)
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
}