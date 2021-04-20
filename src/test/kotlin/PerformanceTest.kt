import org.junit.jupiter.api.Test
import tests.PerformanceTests

class PerformanceTest {

    @Test
    fun performance() {
        PerformanceTests(
            listOf(4, 8),
            listOf(0.1, 0.2),
            listOf(1..1000, 1..100_000),
            seconds = 1).run()
    }
}