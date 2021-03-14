package mine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReentrantLock

class KSkipListConcurrentV1Generic<E>(val k: Int) : AbstractMutableSet<E>() {

    private var comparator: Comparator<E>? = null

    constructor(k: Int, comp: Comparator<E>) : this(k) {
        comparator = comp
    }

    private val sift get() = ThreadLocalRandom.current().nextDouble() < 0.5
    val maxLevel = AtomicInteger(0)

    private val EMPTY: E? = null
    private val tail = Node()
    private val head = Node()

    init {
        head.next.add(tail)
        tail.next.add(tail)
    }

    private fun kArray(): AtomicReferenceArray<E> {
        val atomicIntegerArray = AtomicReferenceArray<E>(k)
        repeat(k) {
            atomicIntegerArray.set(it, EMPTY)
        }
        return atomicIntegerArray
    }

    class Vector<T> {
        @Volatile
        var values: AtomicReferenceArray<T>

        constructor() : this(0)

        constructor(size: Int) {
            values = AtomicReferenceArray<T>(size)
        }

        fun add(v: T) {
            val newAr = AtomicReferenceArray<T>(values.length() + 1)
            for (i in 0 until values.length()) {
                newAr[i] = values[i]
            }
            newAr[values.length()] = v
            values = newAr
        }

        operator fun set(i: Int, v: T) = values.set(i, v)

        operator fun get(i: Int): T = values.get(i)

        val size get() = values.length()

        val indices get() = 0 until size
    }

    inner class Node {
        @Volatile
        var key: AtomicReferenceArray<E> = kArray()

        @Volatile
        var initialMin = EMPTY

        //        val next = CopyOnWriteArrayList<Node>()
        val next = Vector<Node>()

        val lock = ReentrantLock()

        @Volatile
        var deletedBy: CopyOnWriteArrayList<Node?> = CopyOnWriteArrayList<Node?>()

        @Volatile
        var deleted = false

        fun posOfVAndEmpty(v: E): Pair<Int, Int> {
            if (deleted) {
                return -1 to -1
            }
            var posV = -1
            var posEmpty = -1
            for (i in 0 until k) {
                val cur = key.get(i)
                if (posEmpty == -1 && cur == EMPTY) {
                    posEmpty = i
                    continue
                }
                if (posV == -1 && cur != EMPTY && cpr(cur, v) == 0) {
                    posV = i
                }
            }
            return posV to posEmpty
        }

        fun lock() {
            lock.lock()
        }

        fun unlock() {
            lock.unlock()
        }

        fun has(v: E): Boolean {
            val singleReadKey = key
            if (deleted)
                return false
            repeat(k) {
                val cur = singleReadKey.get(it)
                if (cur != EMPTY && cpr(cur,v) == 0) {
                    return true
                }
            }
            return false
        }

        override fun toString(): String {
            return "|${System.identityHashCode(this)};d:$deleted;min:${initialMin}; ${
                (0 until k).map { key.get(it) }.joinToString(", ")
            }, rb=${deletedBy.toArray().map { it?.let { System.identityHashCode(it) } }.joinToString(", ")}}|"
        }

        fun remove(v: E): Boolean {
            var removeRes = false
            var markDeleted = true
            for (i in 0 until k) {
                val curVal = key.get(i)
                if (curVal != EMPTY) {
                    if (cpr(curVal, v) == 0) {
                        key.set(i, EMPTY)
                        removeRes = true
                    } else {
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
        while (cur.deleted && cur.deletedBy.size > level) {
            val next = cur.deletedBy[level]!!
            cur.unlock()

            cur = next
            cur.lock()

        }
        return cur
    }

    private fun moveForwardBlocking(curInp: Node, level: Int, v: E?): Node {
        var cur = firstNotPhysicallyDeleted(curInp, level)
        var next = cur.next[level]

        next.lock()
        while (next !== tail && cpr(next.initialMin, v) <= 0) {
            cur.unlock()
            cur = next
            next = next.next[level]
            next.lock()
        }
        next.unlock()

        return cur
    }

    private fun moveForwardIncludeBlocking(curInp: Node, level: Int, v: E?): Node {
        return moveForwardBlocking(curInp, level, v)
    }

    override fun add(element: E): Boolean {
        var (cur, path) = findAllCandidates(element)
        cur.lock()

        cur = moveForwardIncludeBlocking(cur, 0, element)
        var newNode: Node? = null

        if (cur === head) {
            newNode = Node()
            newNode.lock()
            newNode.key.set(0, element)
            newNode.initialMin = element

            newNode.next.add(cur.next[0])
            cur.next[0] = newNode
            newNode.unlock()
            cur.unlock()
        } else {
            val (vPos, emptyPos) = cur.posOfVAndEmpty(element)
            if (vPos != -1) {
                cur.unlock()
                return false
            } else {
                if (emptyPos == -1) {
                    newNode = Node()
                    //splitting array between nodes

                    var l = 0
                    var r = 0
                    val kArrayL = kArray()
                    for (i in 0 until k) {
                        val curValue = cur.key[i]
                        if (cpr(element, curValue) == -1) {
                            newNode.key[r++] = curValue
                        } else {
                            kArrayL[l++] = curValue
                        }
                    }
                    if (r == k) {
                        kArrayL[0] = element
                        var newMin: E? = null
                        for (i in 0 until k) {
                            newMin = if (newMin == null) {
                                newNode.key[i]
                            } else {
                                if (cpr(newNode.key[i], newMin) >= 0) newMin else newNode.key[i]
                            }
                        }
                        newNode.initialMin = newMin!!
                    } else {
                        newNode.key[r] = element
                        newNode.initialMin = element
                    }
                    newNode.next.add(cur.next[0])
                    newNode.lock()
                    cur.next[0] = newNode
                    cur.key = kArrayL
                    newNode.unlock()
                    cur.unlock()
                } else {
                    cur.key.compareAndSet(emptyPos, EMPTY, element)
                    cur.unlock()
                }
            }
        }
        if (newNode != null) {
            var curLevel = 0
            var increasedHeight = false
            while (sift && !increasedHeight) {
                curLevel++
                cur = if (curLevel >= path.size) head else path[curLevel]!!
                cur.lock()
                if (cur.next.size <= curLevel) {
                    cur.next.add(tail)
                    maxLevel.incrementAndGet()
                    increasedHeight = true
                }
                cur = moveForwardIncludeBlocking(cur, curLevel, newNode.initialMin)
                newNode.lock()

                if (newNode.deleted) {
                    newNode.unlock()
                    cur.unlock()
                    return true
                }
                newNode.next.add(cur.next[curLevel])
                cur.next[curLevel] = newNode
                newNode.unlock()
                cur.unlock()
            }
        }
        return true
    }

    override fun remove(element: E): Boolean {
        var (cur, path) = findAllPrevCandidates(element)
        var curLevel = 0
        cur.lock()
        cur = firstNotPhysicallyDeleted(cur, curLevel)
        var next = cur.next[curLevel]

        next.lock()
        if (next === tail || cpr(next.initialMin, element) == 1) {
            cur.unlock()
            next.unlock()
            return false
        }
        var nextNext = next.next[curLevel]
        nextNext.lock()
        while (nextNext !== tail && cpr(nextNext.initialMin, element) <= 0) {
            cur.unlock()
            cur = next
            next = nextNext
            nextNext = nextNext.next[curLevel]
            nextNext.lock()
        }
        nextNext.unlock()
        val removeRes = next.remove(element)

        if (next.deleted) {
            cur.next[curLevel] = nextNext
            next.deletedBy.add(cur)
            val forDelete = next
            val height = forDelete.next.size
            next.unlock()
            cur.unlock()
            curLevel++
            while (curLevel < height) {
                cur = if (curLevel < path.size) path[curLevel]!! else head
                cur.lock()
                cur = firstNotPhysicallyDeleted(cur, curLevel)
                next = cur.next[curLevel]
                while (next !== forDelete && next !== tail && cpr(next.initialMin, forDelete.initialMin) <= 0) {
                    next.lock()
                    cur.unlock()
                    cur = next
                    next = next.next[curLevel]
                }
                if (next === tail || cpr(next.initialMin, forDelete.initialMin) == 1) {
                    cur.unlock()
                    curLevel++
                    continue
                }

                forDelete.lock()
                cur.next[curLevel] = forDelete.next[curLevel]
                forDelete.deletedBy.add(cur)
                forDelete.unlock()
                cur.unlock()
                curLevel++
            }
            return true
        } else {
            next.unlock()
            cur.unlock()
            return removeRes
        }
    }


    private fun find(v: E): Node {
        var cur = head
        var curDepth = maxLevel.get()
        while (curDepth >= 0) {
            var next = cur.next[curDepth]
            while (next.deleted) {
                next = next.next[curDepth]
            }
            while (next !== tail && cpr(next.initialMin, v) <= 0) {
                cur = next
                next = next.next[curDepth]
                while (next.deleted) {
                    next = next.next[curDepth]
                }
            }
            curDepth--
        }
        return cur
    }

    private fun findAllCandidates(v: E): Pair<Node, Array<Node?>> {
        var cur = head
        var curDepth = maxLevel.get()
        val path = Array<Node?>(curDepth + 1) { null }
        while (curDepth >= 0) {
            var next = cur.next[curDepth]
            while (next !== tail && cpr(next.initialMin, v) <= 0) {
                cur = next
                next = cur.next[curDepth]
            }
            path[curDepth] = cur
            curDepth--
        }
        return cur to path
    }

    private fun findAllPrevCandidates(v: E): Pair<Node, Array<Node?>> {
        var prev = head
        var curDepth = maxLevel.get()
        val path = arrayOfNulls<Node>(curDepth + 1)
        while (curDepth >= 0) {
            var cur = prev
            var next = cur.next[curDepth]
            while (next.deleted) {
                next = next.next[curDepth]
            }
            while (next !== tail && cpr(next.initialMin, v) <= 0) {
                prev = cur
                cur = next
                next = cur.next[curDepth]
                while (next.deleted) {
                    next = next.next[curDepth]
                }
            }
            path[curDepth] = prev
            curDepth--
        }
        return prev to path
    }

    override fun contains(element: E): Boolean {
        var cur = find(element)
        do {
            if (cur.has(element)) {
                return true
            }
            cur = cur.next[0]
        } while (cur !== tail && cpr(cur.initialMin, element) <= 0)
        return false
    }

    fun toList(): List<E> {
        val arrayList = ArrayList<E>()
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

    override val size: Int
        get() = toList().size


    override fun toString(): String {
        val res = StringBuilder()
        for (i in head.next.indices.reversed()) {
            var cur = head
            res.append(cur.toString())
            do {
                res.append(" -> ")
                cur = cur.next[i]
                res.append(cur.toString())
            } while (cur !== tail)
            res.append("\n")
        }
        return res.toString()
    }

    override fun iterator(): MutableIterator<E> {
        TODO("Not yet implemented")
    }

    fun cpr(x: E?, y: E?): Int {
        return comparator?.compare(x, y) ?: (x as Comparable<E?>).compareTo(y)
    }

}