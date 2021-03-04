package mine

import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min
import kotlin.random.Random

class KSkipListV1(val k: Int) {
    private val random = Random(System.currentTimeMillis())
    private val sift get() = random.nextDouble() < 0.5
    var maxLevel = 0
    val levels = ArrayList<Node>()

    private val EMPTY = Int.MAX_VALUE
    private val tail = Node()
    private val head = Node()

    init {
        head.next.add(tail)
        tail.next.add(tail)
    }

    inner class Node {
        val key: IntArray
        var min: Int = EMPTY
        val next = ArrayList<Node>()

        constructor() {
            this.key = IntArray(k) { EMPTY }
        }

        constructor(v: Int) {
            this.key = IntArray(k) { EMPTY }
            this.key[0] = v
        }

        constructor(next: Node, v: Int) {
            this.next.add(next)
            this.key = IntArray(k) { EMPTY }
            this.key[0] = v
        }

        fun has(v: Int) = key.contains(v)

        fun hasFreePlace() = key.any { it == EMPTY }

        override fun toString(): String {
            return "|$min,  ${key.contentToString()} |"
        }

        fun add(v: Int) {
            val indexOf = key.indexOf(EMPTY)
            key[indexOf] = v
            min = min(min, v)
        }

        fun split(path: List<Node>): Pair<Node, Node> {
            //adding to bottom layer
            val node = Node()
            node.next.add(this.next.first())
            this.next[0] = node

            //splitting array between nodes
            this.key.sort()
            val halfK = k / 2
            repeat(halfK) {
                node.key[it] = this.key[halfK + it]
                this.key[halfK + it] = EMPTY
                node.min = min(node.key[it], node.min)
            }
            this.min = this.key.min()!!

            var curLevel = 0
            while (sift) {
                curLevel++
                if (curLevel > maxLevel) {
                    maxLevel = curLevel
                    head.next.add(node)
                    node.next.add(tail)
                    break
                } else {
                    node.next.add(path[curLevel].next[curLevel])
                    path[curLevel].next[curLevel] = node
                }
            }
            return this to node
        }
    }

    fun add(v: Int): Boolean {
        val (cur, path) = findPrev(v)
        if (cur.has(v)) {
            return false
        }
        if (cur === head) {
            val (h, newNode) = cur.split(path)
            check(h === head)
            newNode.add(v)
        } else if (cur.hasFreePlace()) {
            cur.add(v)
        } else {
            val (oldNode, newNode) = cur.split(path)
            check(oldNode === cur)
            if (v < newNode.min) {
                oldNode.add(v)
            } else {
                newNode.add(v)
            }
        }
        return true
    }

    private fun find(v: Int): Node {
        val (prev, _) = findPrev(v)
        return prev
    }

    private fun findPrev(v: Int): Pair<Node, List<Node>> {
        var cur = head
        var curDepth = maxLevel
        val path = ArrayList<Node>()
        while (curDepth >= 0) {
            while (cur.next[curDepth] !== tail && v >= cur.next[curDepth].min) {
                cur = cur.next[curDepth]
            }
            path.add(cur)
            curDepth--
        }
        return cur to path.reversed()
    }

    private fun findPrevRemove(v: Int): Pair<Node, List<Node>> {
        var cur = head
        var curDepth = maxLevel
        var prev = cur
        val path = ArrayList<Node>()
        while (curDepth >= 0) {
            while (cur.next[curDepth] !== tail && v >= cur.next[curDepth].min) {
                prev = cur
                cur = cur.next[curDepth]
            }
            path.add(prev)
            cur = prev
            curDepth--
        }
        return cur to path.reversed()
    }

    fun remove(v: Int): Boolean {
        val (prev, path) = findPrevRemove(v)
        val cur = prev.next[0]
        val indexOfV = cur.key.indexOf(v)
        return if (indexOfV == -1) {
            false
        } else {
            cur.key[indexOfV] = EMPTY
            if (v == cur.min) {
                cur.min = cur.key.min()!!
                if (cur.min == EMPTY) {
                    for (i in path.indices) {
                        if (path[i].next[i] === cur) {
                            path[i].next[i] = cur.next[i]
                        }
                    }
                }
            }
            true
        }
    }

    fun contains(v: Int): Boolean {
        return find(v).has(v)
    }

    fun toList(): List<Int> {
        val arrayList = ArrayList<Int>()
        var cur = head
        while (cur !== tail) {
            arrayList.addAll(cur.key.filter { it != EMPTY })
            cur = cur.next[0]
        }
        return arrayList
    }

    fun toSet() = toList().toSet()

    val size get() = toList().size

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
}