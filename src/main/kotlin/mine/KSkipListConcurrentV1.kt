package mine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

class KSkipListConcurrentV1(val k: Int) {
    companion object {
        const val debug = false
//        const val debug = true
    }

    var opsDone = AtomicInteger(0)

    private val sift get() = ThreadLocalRandom.current().nextDouble() < 0.5
    private val locks: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

    val maxLevel = AtomicInteger(0)

    private val EMPTY = Int.MIN_VALUE
    private val tail = Node()
    private val head = Node()

    init {
        head.next.add(tail)
        tail.next.add(tail)
    }

    private fun kArray(): AtomicIntegerArray {
        val atomicIntegerArray = AtomicIntegerArray(k)
        repeat(k) {
            atomicIntegerArray.set(it, EMPTY)
        }
        return atomicIntegerArray
    }


    inner class Node {
        @Volatile
        var key: AtomicIntegerArray = kArray()

        val min: AtomicInteger = AtomicInteger(EMPTY)
        val next = CopyOnWriteArrayList<Node>()
        val lock = ReentrantLock()

        @Volatile
        var deleted = false


        constructor()

        constructor(v: Int) {
            this.key[0] = v
        }

        constructor(next: Node, v: Int) {
            this.next.add(next)
            this.key[0] = v
        }

        fun posOfVAndEmpty(v: Int): Pair<Int, Int> {
            if (deleted) {
                return -1 to -1
            }
            var posV = -1
            var posEmpty = -1
            for (i in 0 until k) {
                if (posEmpty == -1 && key.get(i) == EMPTY) {
                    posEmpty = i
                    continue
                }
                if (posV == -1 && key.get(i) == v) {
                    posV = i
                }
            }
            return posV to posEmpty
        }

        fun getArray(): IntArray {
            return (0 until k).map { key.get(it) }.filter { it != EMPTY }.toIntArray()
        }

        fun lock() {
            debug {
                locks.set(locks.get() + 1)
//            println(locks.get())
                if (locks.get() > 2) {
                    throw RuntimeException("mnogo lockov")
                }
            }
            lock.lock()
        }

        fun unlock() {
            lock.unlock()
            debug {
                locks.set(locks.get() - 1)
            }
        }

        fun has(v: Int): Boolean {
//            check(!deleted)
            if (deleted)
                return false
            repeat(k) {
                if (key.get(it) == v) {
                    return true
                }
            }
            return false
        }

        override fun toString(): String {
            return "|${System.identityHashCode(this)};d:$deleted;min:${min.get()}; ${
                (0 until k).map { key.get(it) }.joinToString(", ")
            }|"
        }

        fun remove(v: Int): Boolean {
            if (deleted) {
                return false
            }
            if (v != min.get()) {
                var i = 0
                var posV = -1
                while (i < k) {
                    val curVal = key.get(i)
                    if (curVal == v) {
                        posV = i
                        break
                    }
                    i++
                }
                return if (posV != -1) {
                    key.set(posV, EMPTY)
                    true
                } else {
                    false
                }
            } else {
                var i = 0
                var posV = 0
                var newMin: Int? = null
                while (i < k) {
                    val curVal = key.get(i)
                    if (curVal == v) {
                        posV = i
                    } else {
                        if (curVal != EMPTY) {
                            newMin = if (newMin == null) {
                                curVal
                            } else {
                                min(newMin, curVal)
                            }
                        }
                    }
                    i++
                }
                key.set(posV, EMPTY)
                if (newMin == null) {
                    deleted = true
                } else {
                    min.set(newMin)
                }
                return true
            }
        }
    }

    private inline fun moveForward(curInp: Node, level: Int, v: Int, less: (Int, Int) -> Boolean): Node {
        var cur = curInp
        debug {
            check(cur.lock.isHeldByCurrentThread)
            check(cur.min.get() <= v)
        }
        var next = cur.next[level]
        while (next.deleted || next !== tail && less(next.min.get(), v)) {
            debug {
                check(cur.lock.isHeldByCurrentThread)
                check(!next.lock.isHeldByCurrentThread)
                check(cur !== tail)
                check(cur !== next)
                println("V= $v ${Thread.currentThread().name} lock-8 $next  , curLevel = $level")
//                println("Spinning-1")
            }
            if (next.deleted) {
                next.lock()
                cur.next[level] = next.next[level]
                next.unlock()
                next = next.next[level]
            } else {
                next.lock()
                debug {
                    checkTwoNodes(cur, next)
                }
                cur.unlock()
                cur = next
                next = next.next[level]
            }
        }
        return cur
    }

    private fun moveForwardUntilBlocking(curInp: Node, level: Int, v: Int): Node {
        return moveForward(curInp, level, v) { a, b -> a < b }
    }

    private fun moveForwardBlocking(curInp: Node, level: Int, v: Int): Node {
        return moveForward(curInp, level, v) { a, b -> a <= b }
    }

    fun add(v: Int): Boolean {
        debug {
            println("${Thread.currentThread().name} ${locks.get()} ------------------------------------------------------------------------------------------------------------------------")
        }
        var (cur, path) = findAllCandidates(v)

        debug {
            println("V= $v ${Thread.currentThread().name} lock-1 $cur")
        }
        cur.lock()

        cur = moveForwardBlocking(cur, 0, v)

        debug {
            check(cur.lock.isHeldByCurrentThread)
        }
        if (cur.deleted) {
            val newNode = Node()
            debug {
                println("V= $v ${Thread.currentThread().name} lock-11 $newNode")
            }
            newNode.lock()
            newNode.key.set(0, v)
            newNode.min.set(v)

            newNode.next.add(cur.next[0])
            cur.next[0] = newNode

        }
        if (cur === head) {
            val newNode = Node()
            debug {
                println("V= $v ${Thread.currentThread().name} lock-2 $newNode")
            }
            newNode.lock()
            newNode.key.set(0, v)
            newNode.min.set(v)

            newNode.next.add(cur.next[0])
            cur.next[0] = newNode

            var curLevel = 0
            while (sift) {
                curLevel++
                if (curLevel > maxLevel.get()) {
                    newNode.next.add(tail)
                    cur.next.add(newNode)
                    if (!maxLevel.compareAndSet(maxLevel.get(), curLevel)) {
                        throw RuntimeException("error")
                    }
                    break
                } else {
                    newNode.next.add(cur.next[curLevel])
                    cur.next[curLevel] = newNode
                }
            }
            newNode.unlock()
            cur.unlock()
        } else {
            val (vPos, emptyPos) = cur.posOfVAndEmpty(v)
            if (vPos != -1) {
                cur.unlock()
                debug {
                    check(locks.get() == 0)
                    opsDone.incrementAndGet()
                }
                return false
            } else {
                if (emptyPos == -1) {
                    val newNode = Node()
                    debug {
                        check(cur.lock.isHeldByCurrentThread)
                    }
                    //splitting array between nodes
                    val sorted = (0 until k).map { cur.key.get(it) }.toIntArray()
                    sorted.sort()
                    val kArrayL = kArray()
                    for (i in 0 until k / 2) {
                        kArrayL.set(i, sorted[i])
                        newNode.key.set(i, sorted[k / 2 + i])
                    }
                    newNode.min.set(newNode.key.get(0))
                    newNode.next.add(cur.next[0])
                    debug {
                        println("!!V= $v ${Thread.currentThread().name} $newNode")
                    }
                    if (newNode.min.get() < v) {
                        newNode.key.set(k / 2, v)
                    } else {
                        kArrayL.set(k / 2, v)
                    }
                    cur.next[0] = newNode
                    cur.key = kArrayL

                    debug {

                        check(newNode.min.get() != v)
                        check(cur.min.get() != v)
                    }

                    cur.unlock()
                    var curLevel = 0
                    while (sift) {
//                        debug {
//                            println("Spinning3")
//                        }
                        curLevel++
                        if (curLevel > maxLevel.get()) {
                            debug {
                                println("V= $v ${Thread.currentThread().name} lock-3 $head , curLevel = $curLevel")
                            }
                            head.lock()

                            val curMaxLevel = maxLevel.get()
                            if (curLevel > curMaxLevel) {
                                debug {
                                    println("V= $v ${Thread.currentThread().name} lock-4 $newNode , curLevel = $curLevel")
                                }
                                newNode.lock()
                                debug {
                                    checkTwoNodes(head, newNode)
                                }
                                newNode.next.add(tail)
                                head.next.add(newNode)
                                if (!maxLevel.compareAndSet(curMaxLevel, curLevel)) {
                                    throw RuntimeException("ads")
                                }
                                newNode.unlock()
                                head.unlock()
                                break
                            } else {
                                cur = head
                                debug {
                                    check(cur.lock.isHeldByCurrentThread)
                                }
                                cur = moveForwardUntilBlocking(cur, curLevel, newNode.min.get())
                                debug {
                                    check(cur.lock.isHeldByCurrentThread)
                                    println("V= $v ${Thread.currentThread().name} lock-5 $newNode , curLevel = $curLevel")
                                }
                                newNode.lock()
                                debug {
                                    checkTwoNodes(cur, newNode)
                                }
                                newNode.next.add(cur.next[curLevel])
                                cur.next[curLevel] = newNode
                                newNode.unlock()
                                cur.unlock()
                            }
                        } else {
                            cur = if (curLevel >= path.size) head else path[curLevel]!!
                            debug {
                                println("V= $v ${Thread.currentThread().name} lock-6 $cur , curLevel = $curLevel")
                            }
                            cur.lock()


                            cur = moveForwardUntilBlocking(cur, curLevel, newNode.min.get())
                            debug {
                                check(cur.lock.isHeldByCurrentThread)
                                println("V= $v ${Thread.currentThread().name} lock-7 $newNode , curLevel = $curLevel")
                            }
                            newNode.lock()
                            debug {
                                checkTwoNodes(cur, newNode)
                            }
                            newNode.next.add(cur.next[curLevel])
                            cur.next[curLevel] = newNode
                            newNode.unlock()
                            cur.unlock()
                        }
                    }
                } else {
                    if (!cur.key.compareAndSet(emptyPos, EMPTY, v)) {
                        throw RuntimeException("error")
                    }
                    cur.unlock()
                    debug {
                        check(locks.get() == 0)
                    }
                }
            }
        }
        debug {
            check(locks.get() == 0)
            opsDone.incrementAndGet()
        }
        return true
    }

    private fun moveForwardToPrevBlocking(curPrev: Node, level: Int, v: Int): Pair<Node, Node> {
        var prev = curPrev
        var cur = prev
        debug {
            check(prev.lock.isHeldByCurrentThread)
            check(prev.min.get() <= v)
        }
        var next = cur.next[level]

        while (next !== tail && next.min.get() <= v) {
            debug {
                check(prev.lock.isHeldByCurrentThread)
                check(!cur.lock.isHeldByCurrentThread)
                println("V= $v ${Thread.currentThread().name} lock-8 $cur , curLevel = $level")
//                println("Spinning-1")
            }
            prev.unlock()
            next.lock()
            prev = cur
            cur = next
            next = next.next[level]
        }
        return prev to cur
    }

    fun remove(v: Int): Boolean {
        var cur = find(v)
        cur.lock()
        cur = moveForwardBlocking(cur, 0, v)
        val res = cur.remove(v)
        cur.unlock()
        return res
    }


    private fun find(v: Int): Node {
        var cur = head
        var curDepth = maxLevel.get()
//        var curDepth = 0
        while (curDepth >= 0) {
            var next = cur.next[curDepth]
            while (next.deleted || next !== tail && v >= next.min.get()) {
                debug {
                    println("cur $cur")
                    println("next $next")
                    println("Spinning6")
                    println("value $v")
                    println(toString())
                }
                if (!next.deleted) {
                    cur = next
                }
                next = next.next[curDepth]
            }
            curDepth--
        }
//        check(!cur.deleted)
        return cur
    }

    private fun findAllCandidates(v: Int): Pair<Node, Array<Node?>> {
        var cur = head
        var curDepth = maxLevel.get()
        val path = Array<Node?>(curDepth + 1) { null }
        while (curDepth >= 0) {
            var next = cur.next[curDepth]
            while (next !== tail && next.min.get() <= v) {
                debug {
//                    println("Spinning7")
//                    println("value $v")
//                    println(toString())
                }
                cur = next
                next = cur.next[curDepth]
            }
            path[curDepth] = cur
//            if (curDepth > 0) path.add(cur)
            curDepth--
        }
        return cur to path
    }

    fun contains(v: Int): Boolean {
        var cur = find(v)
        do {
            if (cur.has(v)) {
                return true
            }
            cur = cur.next[0]
        } while (cur !== tail && cur.min.get() <= v)
        return false
    }

    fun toList(): List<Int> {
        val arrayList = ArrayList<Int>()
        var cur = head
        while (cur !== tail) {
            repeat(k) {
                if (cur.key.get(it) != EMPTY) {
                    arrayList.add(cur.key.get(it))
                }
            }
            cur = cur.next[0]
        }
        return arrayList
    }

    fun toSet() = toList().toSet()

    val size get() = toList().size

    private fun checkTwoNodes(cur: Node, next: Node) {
        check(
            cur.getArray().toSet()
                .intersect(
                    next.getArray().toSet()
                ).isEmpty()
        )
        check(cur.getArray().all { f -> next.getArray().all { f < it } }) { "cur $cur \n next $next" }
    }

    override fun toString(): String {
        var res = ""
        for (i in head.next.indices.reversed()) {
            var cur = head
            res += cur
            do {
                res += " -> "
                cur = cur.next[i]
                res += cur
            } while (cur !== tail)
            res += "\n"
        }
        return res
    }

    fun size() = size

    private fun debug(f: Runnable) {
        if (debug) {
            f.run()
        }
    }
}