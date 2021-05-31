import kset.ConcurrentSkipListSetInt
import kotlin.random.Random


val rolling = listOf('|', '/', '–', '\\')

fun main() {
    val random = Random(13234)
//    val skipList = KSkipListConcurrentInt()
    val skipList = ConcurrentSkipListSetInt()
//    val skipList = KSkipListConcurrentGeneric<Int>()
    val values = 1..5_000_000;
    values.shuffled(random).forEach { skipList.add(it) }
    var curPos = 0
    System.gc()
    while (true) {
        print(rolling[curPos++ % 4] + "\r")
        Thread.sleep(1000)
    }
}