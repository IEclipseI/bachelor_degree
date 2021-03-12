package mine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantLock

class KSkipListConcurrentV1(val k: Int) : AbstractMutableSet<Int>() {
    companion object {
        const val debug = false
//        const val debug = true

        const val debugPrint = false
//        const val debugPrint = true

    }

    val range = 0 until k
    private var opsDoneV = AtomicInteger(0)
    val opsDone get() = opsDoneV.get()

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

        @Volatile
        var initialMin = EMPTY
        val next = CopyOnWriteArrayList<Node>()
        val lock = ReentrantLock()

        @Volatile
        var deletedBy: CopyOnWriteArrayList<Node?> = CopyOnWriteArrayList<Node?>()

        @Volatile
        var deleted = false

        fun posOfVAndEmpty(v: Int): Pair<Int, Int> {
            debug {
                check(lock.isHeldByCurrentThread)
                check(this !== tail)
            }
            if (deleted) {
                return -1 to -1
            }
            debug {
                check(min.get() <= v) { "$this, $v" }
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
            debug {
                val ar = getFullArray()
                check(posV == ar.indexOf(v))
                check(posEmpty == ar.indexOf(EMPTY))
            }
            return posV to posEmpty
        }

        fun getArray(): IntArray {
            return (0 until k).map { key.get(it) }.filter { it != EMPTY }.toIntArray()
        }

        fun getFullArray(): IntArray {
            return (0 until k).map { key.get(it) }.toIntArray()
        }

        fun lock() {
            debug {
//            println(locks.get())
                if (lock.holdCount > 0) {
                    println("TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT")
                    throw RuntimeException("mnogo lockov")
                }
                locks.set(locks.get() + 1)
                if (locks.get() > 3) {
                    println("TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT")
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
            val singleReadKey = key
            debug {
                check(this !== tail)
            }
//            check(!deleted)
            if (deleted)
                return false
            repeat(k) {
                if (singleReadKey.get(it) == v) {
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
            debug {
                check(this !== tail)
                check(!deleted)
                check(lock.isHeldByCurrentThread)
            }
            var removeRes = false
            var markDeleted = true
            for (i in 0 until k) {
                val curVal = key.get(i)
                if (curVal == v) {
                    key.set(i, EMPTY)
                    removeRes = true
                } else {
                    if (curVal != EMPTY) {
                        markDeleted = false
                    }
                }
            }
            deleted = markDeleted
            return removeRes
        }
    }

    private fun firstNotPhysicallyDeleted(curInp: Node, level: Int): Node {
        var cur = curInp
        debug {
            check(locks.get() == 1)
            check(cur.lock.isHeldByCurrentThread)
        }
        while (cur.deleted && cur.deletedBy.size > level) {
            val next = cur.deletedBy[level]!!
            cur.unlock()

            cur = next
            debug {
                debugPrint("V= _ ${Thread.currentThread().name} lock-11 $cur , curLevel = $level, lc=${locks.get()}")
            }
            cur.lock()

        }
        debug {
            check(cur.deletedBy.size <= level)
        }
        return cur
    }

    private fun moveForwardBlocking(curInp: Node, level: Int, v: Int, comp: Comparator<Int>): Node {
        debug {
            check(curInp.lock.isHeldByCurrentThread)
        }
        var cur = firstNotPhysicallyDeleted(curInp, level)
        debug {
            check(cur.lock.isHeldByCurrentThread)
        }
        var next = cur.next[level]
        debug {
            debugPrint("V= $v ${Thread.currentThread().name} lock-1 $next  , curLevel = $level, lc=${locks.get()}")
        }
        next.lock()
        debug {
            check(next.deletedBy.size <= level)
            checkTwoNodes(cur, next, level)
        }
        while (next !== tail && (comp.compare(next.initialMin, v) <= 0
                    || next.deleted && comp.compare(next.initialMin, v) == 0)
        ) {
            debug {
                check(cur.lock.isHeldByCurrentThread)
//                check(!next.lock.isHeldByCurrentThread)
                check(cur !== tail)
                check(cur !== next)
                check(next.deletedBy.size <= level)
//                println("Spinning-1")
            }
            debug {
                checkTwoNodes(cur, next, level)
            }
            cur.unlock()
            cur = next
            next = next.next[level]
            debug {
                debugPrint("V= $v ${Thread.currentThread().name} lock-2 $next  , curLevel = $level, lc=${locks.get()}")
            }
            next.lock()
            debug {
                check(next.deletedBy.size <= level)
            }
        }
        next.unlock()

        debug {
            check(cur.lock.isHeldByCurrentThread)
            check(cur.deletedBy.size <= level)
            check(locks.get() == 1)
        }
        return cur
    }

    private fun moveForwardUntilBlocking(curInp: Node, level: Int, v: Int): Node {
        return moveForwardBlocking(curInp, level, v) { a, b -> a - b + 1 } //TODO
    }

    private fun moveForwardIncludeBlocking(curInp: Node, level: Int, v: Int): Node {
        return moveForwardBlocking(curInp, level, v) { a, b -> a - b }
    }

    override fun add(v: Int): Boolean {
        debug {
            debugPrint("${Thread.currentThread().name} ${locks.get()} ------------------------------------------------------------------------------------------------------------------------")
        }
        var (cur, path) = findAllCandidates(v)

        debug {
            debugPrint("V= $v ${Thread.currentThread().name} lock-0 $cur")
        }
        cur.lock()

        debug {
            check(cur.initialMin <= v) { "$cur, $v" }

            check(cur.lock.isHeldByCurrentThread)
        }
        cur = moveForwardIncludeBlocking(cur, 0, v)

        debug {
            check(cur.initialMin <= v) { "$cur, $v" }

            check(cur.lock.isHeldByCurrentThread)
        }
        if (cur === head) {
            val newNode = Node()
            debug {
                debugPrint("V= $v ${Thread.currentThread().name} lock-3 $newNode, lc=${locks.get()}")
            }
            newNode.lock()
            newNode.key.set(0, v)
            newNode.min.set(v)
            newNode.initialMin = v

            newNode.next.add(cur.next[0])
            cur.next[0] = newNode

            var curLevel = 0
//            while (sift) {
//                curLevel++
//                if (curLevel > maxLevel.get()) {
//                    newNode.next.add(tail)
//                    cur.next.add(newNode)
//                    if (!maxLevel.compareAndSet(maxLevel.get(), curLevel)) {
//                        throw RuntimeException("error")
//                    }
//                    break
//                } else {
//                    newNode.next.add(cur.next[curLevel])
//                    cur.next[curLevel] = newNode
//                }
//            }
            newNode.unlock()
            cur.unlock()
        } else {
            val (vPos, emptyPos) = cur.posOfVAndEmpty(v)
            if (vPos != -1) {
                debug {
                    check(!cur.deleted)
                }
                cur.unlock()
                debug {
                    check(locks.get() == 0)
                    opsDoneV.incrementAndGet()
                }
                return false
            } else {
                if (emptyPos == -1) {
                    val newNode = Node()
                    debug {
                        check(cur.lock.isHeldByCurrentThread)
                        check(!cur.deleted)
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
                    newNode.initialMin = newNode.key.get(0)
                    newNode.next.add(cur.next[0])
                    debug {
                        check(newNode.min.get() != EMPTY)
                    }
                    if (newNode.initialMin < v) {
                        newNode.key.set(k / 2, v)
                    } else {
                        kArrayL.set(k / 2, v)
                    }
                    debug {
                        debugPrint("V= $v ${Thread.currentThread().name} lock-4 $newNode, lc=${locks.get()}")
                    }
                    newNode.lock()
                    cur.next[0] = newNode
                    cur.key = kArrayL
                    val minToSearch = newNode.min.get()
                    debug {
                        checkTwoNodes(cur, newNode, 0)
                        check(newNode.min.get() != v)
                        check(cur.min.get() != v)
                    }
                    newNode.unlock()
                    cur.unlock()
                    var curLevel = 0
                    var increasedHeight = false
//                    while (sift && !increasedHeight) {
//                        curLevel++
//                        cur = if (curLevel >= path.size) head else path[curLevel]!!
//                        debug {
//                            debugPrint("V= $v ${Thread.currentThread().name} lock-5 $cur, lc=${locks.get()}")
//                        }
//                        cur.lock()
//                        if (cur.next.size <= curLevel) {
//                            debug {
//                                check(cur === head)
//                            }
//                            cur.next.add(tail)
//                            maxLevel.incrementAndGet()
//                            increasedHeight = true
//                        }
//                        cur = moveForwardIncludeBlocking(cur, curLevel, minToSearch)
////                        debug {
////                            check(cur.next[curLevel].min.get() != cur.min.get())
////                        }
//                        debug {
//                            debugPrint("V= $v ${Thread.currentThread().name} lock-6 $newNode, lc=${locks.get()}")
//                        }
//                        newNode.lock()
//                        debug {
//                            checkTwoNodes(cur, newNode, curLevel)
//                        }
//                        if (newNode.deleted) {
//                            newNode.unlock()
//                            cur.unlock()
//                            debug {
//                                opsDoneV.incrementAndGet()
//                            }
//                            return true
//                        }
//                        newNode.next.add(cur.next[curLevel])
//                        cur.next[curLevel] = newNode
//                        newNode.unlock()
//                        cur.unlock()
//                    }
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
            opsDoneV.incrementAndGet()
        }
        return true
    }

    override fun remove(element: Int): Boolean {
        debug {
            debugPrint("${Thread.currentThread().name} ${locks.get()} ------------------------------------------------------------------------------------------------------------------------")
        }
        var (cur, path) = findAllPrevCandidates(element)
        var curLevel = 0
        debug {
            debugPrint("V= $element ${Thread.currentThread().name} lock-10 $cur , curLevel = $curLevel, lc=${locks.get()}")
        }
        cur.lock()
        cur = firstNotPhysicallyDeleted(cur, 0)
        debug {
            check(!(cur.min.get() == 2 && cur.deleted && cur.deletedBy.size > 0)) //for 2 only
        }
        debug {
//            check(cur.min.get() != 2) //for 2 only
            check(!cur.deleted)
            check(cur.lock.isHeldByCurrentThread)
        }
        if (cur.min.get() >= element) {
            cur.unlock()
            debug {
                check(locks.get() == 0)
                opsDoneV.incrementAndGet()
            }
            return false
        }
        debug {
            check(cur.lock.isHeldByCurrentThread)
        }
        var next = cur.next[curLevel]
        debug {
            debugPrint("V= $element ${Thread.currentThread().name} lock-12 $next , curLevel = $curLevel, lc=${locks.get()}")
        }
        next.lock()
        debug {
            check(next.deletedBy.size == 0)
            checkTwoNodes(cur, next, curLevel)
        }
        if (next === tail || next.initialMin > element) {
            debug {
                check(next.min.get() != element)
            }
            cur.unlock()
            next.unlock()
            debug {
                check(locks.get() == 0)
                opsDoneV.incrementAndGet()
            }
            return false
        }

        var nextNext = next.next[curLevel]
        debug {
            debugPrint("V= $element ${Thread.currentThread().name} lock-13 $nextNext , curLevel = $curLevel, lc=${locks.get()}")
            check(nextNext.deletedBy.size == 0)
        }
        nextNext.lock()
        while (nextNext !== tail && nextNext.initialMin <= element) {
            cur.unlock()
            cur = next
            next = nextNext
            debug {
                check(nextNext.deletedBy.size == 0)
                checkTwoNodes(cur, next, curLevel)
            }
            nextNext = nextNext.next[curLevel]
            debug {
                debugPrint("V= $element ${Thread.currentThread().name} lock-14 $nextNext , curLevel = $curLevel, lc=${locks.get()}")
            }
            nextNext.lock()
        }
        nextNext.unlock()
        debug {
            check(cur.deletedBy.size <= curLevel)
            check(next !== tail)
            check(!next.deleted)
            check(next.lock.isHeldByCurrentThread)
            check(cur.lock.isHeldByCurrentThread)
        }
        val removeRes = next.remove(element)

        if (next.deleted) {
            cur.next[curLevel] = nextNext
            next.deletedBy.add(cur)
            next.next[curLevel] = cur //experiment
            val forDelete = next
            val height = forDelete.next.size
            next.unlock()
            cur.unlock()
//            curLevel++
//            while (curLevel < height) {
//                cur = if (curLevel < path.size) path[curLevel]!! else head
//                debug {
//                    debugPrint("V= $element ${Thread.currentThread().name} lock-15 $cur , curLevel = $curLevel, lc=${locks.get()}")
//                }
//                cur.lock()
//                cur = firstNotPhysicallyDeleted(cur, curLevel)
//                debug {
//                    check(cur.lock.isHeldByCurrentThread)
//                }
//                next = cur.next[curLevel]
//                debug {
//                    check(cur.deletedBy.size <= curLevel)
//                    check(next.deletedBy.size <= curLevel)
//                    check(next.next.size > curLevel)
//                }
//                debug {
//                    check(next !== tail) { curLevel }
//                }
//                while (next !== forDelete) {
//                    debug {
//                        debugPrint("V= $element ${Thread.currentThread().name} lock-16 $next , curLevel = $curLevel, lc=${locks.get()}")
//                    }
//                    next.lock()
//                    debug {
//
//                        check(next !== tail)
//                        check(cur.lock.isHeldByCurrentThread)
//                        checkTwoNodes(cur, next, curLevel)
//                    }
//                    cur.unlock()
//                    cur = next
//                    next = next.next[curLevel]
//                    debug {
//                        check(next !== tail)
//                    }
//                }
//
//                debug {
//                    debugPrint("V= $element ${Thread.currentThread().name} lock-17 $forDelete , curLevel = $curLevel, lc=${locks.get()}")
//                }
//                forDelete.lock()
//
//                debug {
//                    check(cur.lock.isHeldByCurrentThread)
//                    checkTwoNodes(cur, forDelete, curLevel)
//                }
//                cur.next[curLevel] = forDelete.next[curLevel]
//                forDelete.deletedBy.add(cur)
//                forDelete.next[curLevel] = cur //experiment
//                debug {
//                    check(next.deletedBy.size == curLevel + 1)
//                }
//                next.unlock()
//                cur.unlock()
//                curLevel++
//            }
//            debug {
//                opsDoneV.incrementAndGet()
//                check(locks.get() == 0)
//            }
            return true
        } else {
            debug {
                check(!(next.min.get() == element && !next.deleted && !removeRes))
            }
            next.unlock()
            cur.unlock()
            debug {
                opsDoneV.incrementAndGet()
                check(locks.get() == 0)
            }
            return removeRes
        }
    }


    private fun find(v: Int): Node {
        var cur = head
        var curDepth = maxLevel.get()
//        var curDepth = 0
        while (curDepth >= 0) {
            var next = cur.next[curDepth]
            while (next !== tail && next.initialMin <= v) {
                debug {
//                    println("cur $cur")
//                    println("next $next")
//                    println("Spinning6")
//                    println("value $v")
//                    println(toString())
                }
//                if (!next.deleted) {
//                }
                cur = next
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
            while (next !== tail && next.initialMin <= v) {
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

    private fun findAllPrevCandidates(v: Int): Pair<Node, Array<Node?>> {
        var prev = head
        var cur = head
        var curDepth = maxLevel.get()
        val path = arrayOfNulls<Node>(curDepth + 1)
        while (curDepth >= 0) {
            var next = cur.next[curDepth]
            while (next !== tail && next.initialMin <= v) {
                debug {
//                    println("Spinning7")
//                    println("value $v")
//                    println(toString())
                }
                prev = cur
                cur = next
                next = cur.next[curDepth]
            }
            path[curDepth] = prev
//            if (curDepth > 0) path.add(cur)
            curDepth--
        }
        debug {
            check(prev.min.get() <= v)
        }
        return prev to path
    }

    override fun contains(v: Int): Boolean {
        var cur = find(v)
        do {
            if (cur.has(v)) {
                return true
            }
            cur = cur.next[0]
        } while (cur !== tail && cur.initialMin <= v)
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
        return arrayList.sorted()
    }

    fun toSet() = toList().toSet()

    override val size: Int
        get() = toList().size

    private fun checkTwoNodes(cur: Node, next: Node, level: Int) {

//        check(cur.next[level] === next) { "cur $cur \n next $next level $level" }
        check(next === tail || cur.min.get() <= next.min.get()) { "cur $cur \n next $next level $level" }
        check(
            cur.getArray().toSet()
                .intersect(
                    next.getArray().toSet()
                ).isEmpty()
        ) { "cur $cur \n next $next level $level" }
        check(cur.getArray().all { f -> next.getArray().all { f < it } }) { "cur $cur \n next $next level $level" }
    }

    override fun toString(): String {
        var res = StringBuilder()
        for (i in head.next.indices.reversed()) {
            var cur = head
//            cur.lock()
            res.append(cur.toString())
//            cur.unlock()
            do {
                res.append(" -> ")
                cur = cur.next[i]
//                cur.lock()
                res.append(cur.toString())
//                cur.unlock()
            } while (cur !== tail)
            res.append("\n")
        }
        return res.toString()
    }

    private fun debug(f: Runnable) {
        if (debug) {
            f.run()
        }
    }

    private fun debugPrint(msg: String) {
        if (debugPrint) {
            println(msg)
            System.out.flush()
        }
    }

    override fun iterator(): MutableIterator<Int> {
        TODO("Not yet implemented")
    }
}