package plot_utils

import java.io.File

const val slurm_filename = "slurm-2585991"

fun main() {
    val text = File("performance_tests/$slurm_filename.out").readLines().filter { it.isNotBlank() }
    val grouppedByValues = text.joinToString("\n").split(Regex("\n(#+\n)+")).dropLast(1)
    grouppedByValues.forEach { oneValueRange(it) }
//    println(text1)
}

fun oneValueRange(inp: String) {
    val lines = inp.lines()
    val updateRatio = lines.filter { it.startsWith("Threads: ") }
        .map { it.split(", ").last() }
        .map { it.split(": ").last() }
        .toSet()
    val threads = lines.filter { it.startsWith("Threads: ") }
        .map { it.split(", ").first() }
        .map { it.split(": ").last() }
        .toSet()

    val byRatio = updateRatio.map { it to ArrayList<List<String>>() }.toMap().toMutableMap()



    println(updateRatio)
    println(threads)

    var ss = lines.drop(1).joinToString("\n").split(Regex("\n–+\n"))
    var structures = emptyList<String>()
    for (s in ss) {
        val thread = s.lines().first().split(", ").first().split(": ").last()
        val ratio = s.lines().first().split(", ").last().split(": ").last()


        byRatio[ratio]?.add(listOf(thread) + s.lines().drop(1).map { it.split(Regex("\\s+")).last() })
        structures = s.lines().drop(1).map { it.split(Regex("\\s+")).first() }
        println(s)
    }
    byRatio.forEach { (ratio, list) ->
        val filename = "${slurm_filename}_${lines[0]}_values_$ratio.csv"
        val file = File("plot_csv", filename)
        if (file.exists())
            file.delete()
        file.createNewFile()
        file.appendText(structures.drop(3).joinToString(", ") + "\n")
        list.forEach { file.appendText((listOf(it[0]) + it.drop(4)).joinToString(",") + "\n") }
//        file.writeText()
    }
}