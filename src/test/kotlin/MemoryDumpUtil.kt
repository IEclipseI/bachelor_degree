import mine.KSkipListConcurrentGeneric


val rolling = listOf('|', '/', '–', '\\')

fun main() {
    val skipList = KSkipListConcurrentGeneric<Int>()
//    val skipList = ConcurrentSkipListSet<Int>()
//    val skipList = KSkipListConcurrentGeneric<Int>()
    val values = 1..5_000_000;
    values.shuffled().forEach { skipList.add(it) }
    var curPos = 0
    System.gc()
    while (true) {
        print(rolling[curPos++ % 4] + "\r")
        Thread.sleep(1000)

    }
}