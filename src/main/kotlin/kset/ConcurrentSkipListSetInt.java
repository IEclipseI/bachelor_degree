package kset;

/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 */

import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;


public class ConcurrentSkipListSetInt extends AbstractSet<Integer> {
    /*
     * This class implements a tree-like two-dimensionally linked skip list in
     * which the index levels are represented in separate nodes from the base
     * nodes holding data. There are two reasons for taking this approach
     * instead of the usual array-based structure: 1) Array based
     * implementations seem to encounter more complexity and overhead 2) We can
     * use cheaper algorithms for the heavily-traversed index lists than can be
     * used for the base lists. Here's a picture of some of the basics for a
     * possible list with 2 levels of index:
     *
     * Head nodes Index nodes +-+ right +-+ +-+ |2|---------------->|
     * |--------------------->| |->null +-+ +-+ +-+ | down | | v v v +-+ +-+ +-+
     * +-+ +-+ +-+ |1|----------->| |->| |------>| |----------->| |------>|
     * |->null +-+ +-+ +-+ +-+ +-+ +-+ v | | | | | Nodes next v v v v v +-+ +-+
     * +-+ +-+ +-+ +-+ +-+ +-+ +-+ +-+ +-+ +-+ |
     * |->|A|->|B|->|C|->|D|->|E|->|F|->|G|->|H|->|I|->|J|->|K|->null +-+ +-+
     * +-+ +-+ +-+ +-+ +-+ +-+ +-+ +-+ +-+ +-+
     *
     * The base lists use a variant of the HM linked ordered set algorithm. See
     * Tim Harris, "A pragmatic implementation of non-blocking linked lists"
     * http://www.cl.cam.ac.uk/~tlh20/publications.html and Maged Michael "High
     * Performance Dynamic Lock-Free Hash Tables and List-Based Sets"
     * http://www.research.ibm.com/people/m/michael/pubs.htm. The basic idea in
     * these lists is to mark the "next" pointers of deleted nodes when deleting
     * to avoid conflicts with concurrent insertions, and when traversing to
     * keep track of triples (predecessor, node, successor) in order to detect
     * when and how to unlink these deleted nodes.
     *
     * Rather than using mark-bits to mark list deletions (which can be slow and
     * space-intensive using AtomicMarkedReference), nodes use direct CAS'able
     * next pointers. On deletion, instead of marking a pointer, they splice in
     * another node that can be thought of as standing for a marked pointer
     * (indicating this by using otherwise impossible field values). Using plain
     * nodes acts roughly like "boxed" implementations of marked pointers, but
     * uses new nodes only when nodes are deleted, not for every link. This
     * requires less space and supports faster traversal. Even if marked
     * references were better supported by JVMs, traversal using this technique
     * might still be faster because any search need only read ahead one more
     * node than otherwise required (to check for trailing marker) rather than
     * unmasking mark bits or whatever on each read.
     *
     * This approach maintains the essential property needed in the HM algorithm
     * of changing the next-pointer of a deleted node so that any other CAS of
     * it will fail, but implements the idea by changing the pointer to point to
     * a different node, not by marking it. While it would be possible to
     * further squeeze space by defining marker nodes not to have key/value
     * fields, it isn't worth the extra type-testing overhead. The deletion
     * markers are rarely encountered during traversal and are normally quickly
     * garbage collected. (Note that this technique would not work well in
     * systems without garbage collection.)
     *
     * In addition to using deletion markers, the lists also use nullness of
     * value fields to indicate deletion, in a style similar to typical
     * lazy-deletion schemes. If a node's value is null, then it is considered
     * logically deleted and ignored even though it is still reachable. This
     * maintains proper control of concurrent replace vs delete operations -- an
     * attempted replace must fail if a delete beat it by nulling field, and a
     * delete must return the last non-null value held in the field. (Note:
     * Null, rather than some special marker, is used for value fields here
     * because it just so happens to mesh with the Map API requirement that
     * method get returns null if there is no mapping, which allows nodes to
     * remain concurrently readable even when deleted. Using any other marker
     * value here would be messy at best.)
     *
     * Here's the sequence of events for a deletion of node n with predecessor b
     * and successor f, initially:
     *
     * +------+ +------+ +------+ ... | b |------>| n |----->| f | ... +------+
     * +------+ +------+
     *
     * 1. CAS n's value field from non-null to null. From this point on, no
     * public operations encountering the node consider this mapping to exist.
     * However, other ongoing insertions and deletions might still modify n's
     * next pointer.
     *
     * 2. CAS n's next pointer to point to a new marker node. From this point
     * on, no other nodes can be appended to n. which avoids deletion errors in
     * CAS-based linked lists.
     *
     * +------+ +------+ +------+ +------+ ... | b |------>| n
     * |----->|marker|------>| f | ... +------+ +------+ +------+ +------+
     *
     * 3. CAS b's next pointer over both n and its marker. From this point on,
     * no new traversals will encounter n, and it can eventually be GCed.
     * +------+ +------+ ... | b |----------------------------------->| f | ...
     * +------+ +------+
     *
     * A failure at step 1 leads to simple retry due to a lost race with another
     * operation. Steps 2-3 can fail because some other thread noticed during a
     * traversal a node with null value and helped out by marking and/or
     * unlinking. This helping-out ensures that no thread can become stuck
     * waiting for progress of the deleting thread. The use of marker nodes
     * slightly complicates helping-out code because traversals must track
     * consistent reads of up to four nodes (b, n, marker, f), not just (b, n,
     * f), although the next field of a marker is immutable, and once a next
     * field is CAS'ed to point to a marker, it never again changes, so this
     * requires less care.
     *
     * Skip lists add indexing to this scheme, so that the base-level traversals
     * start close to the locations being found, inserted or deleted -- usually
     * base level traversals only traverse a few nodes. This doesn't change the
     * basic algorithm except for the need to make sure base traversals start at
     * predecessors (here, b) that are not (structurally) deleted, otherwise
     * retrying after processing the deletion.
     *
     * Index levels are maintained as lists with volatile next fields, using CAS
     * to link and unlink. Races are allowed in index-list operations that can
     * (rarely) fail to link in a new index node or delete one. (We can't do
     * this of course for data nodes.) However, even when this happens, the
     * index lists remain sorted, so correctly serve as indices. This can impact
     * performance, but since skip lists are probabilistic anyway, the net
     * result is that under contention, the effective "p" value may be lower
     * than its nominal value. And race windows are kept small enough that in
     * practice these failures are rare, even under a lot of contention.
     *
     * The fact that retries (for both base and index lists) are relatively
     * cheap due to indexing allows some minor simplifications of retry logic.
     * Traversal restarts are performed after most "helping-out" CASes. This
     * isn't always strictly necessary, but the implicit backoffs tend to help
     * reduce other downstream failed CAS's enough to outweigh restart cost.
     * This worsens the worst case, but seems to improve even highly contended
     * cases.
     *
     * Unlike most skip-list implementations, index insertion and deletion here
     * require a separate traversal pass occuring after the base-level action,
     * to add or remove index nodes. This adds to single-threaded overhead, but
     * improves contended multithreaded performance by narrowing interference
     * windows, and allows deletion to ensure that all index nodes will be made
     * unreachable upon return from a public remove operation, thus avoiding
     * unwanted garbage retention. This is more important here than in some
     * other data structures because we cannot null out node fields referencing
     * user keys since they might still be read by other ongoing traversals.
     *
     * Indexing uses skip list parameters that maintain good search performance
     * while using sparser-than-usual indices: The hardwired parameters k=1,
     * p=0.5 (see method randomLevel) mean that about one-quarter of the nodes
     * have indices. Of those that do, half have one level, a quarter have two,
     * and so on (see Pugh's Skip List Cookbook, sec 3.4). The expected total
     * space requirement for a map is slightly less than for the current
     * implementation of java.util.TreeMap.
     *
     * Changing the level of the index (i.e, the height of the tree-like
     * structure) also uses CAS. The head index has initial level/height of one.
     * Creation of an index with height greater than the current level adds a
     * level to the head index by CAS'ing on a new top-most head. To maintain
     * good performance after a lot of removals, deletion methods heuristically
     * try to reduce the height if the topmost levels appear to be empty. This
     * may encounter races in which it possible (but rare) to reduce and "lose"
     * a level just as it is about to contain an index (that will then never be
     * encountered). This does no structural harm, and in practice appears to be
     * a better option than allowing unrestrained growth of levels.
     *
     * The code for all this is more verbose than you'd like. Most operations
     * entail locating an element (or position to insert an element). The code
     * to do this can't be nicely factored out because subsequent uses require a
     * snapshot of predecessor and/or successor and/or value fields which can't
     * be returned all at once, at least not without creating yet another object
     * to hold them -- creating such little objects is an especially bad idea
     * for basic internal search operations because it adds to GC overhead.
     * (This is one of the few times I've wished Java had macros.) Instead, some
     * traversal code is interleaved within insertion and removal operations.
     * The control logic to handle all the retry conditions is sometimes twisty.
     * Most search is broken into 2 parts. findPredecessor() searches index
     * nodes only, returning a base-level predecessor of the key. findNode()
     * finishes out the base-level search. Even with this factoring, there is a
     * fair amount of near-duplication of code to handle variants.
     *
     * For explanation of algorithms sharing at least a couple of features with
     * this one, see Mikhail Fomitchev's thesis
     * (http://www.cs.yorku.ca/~mikhail/), Keir Fraser's thesis
     * (http://www.cl.cam.ac.uk/users/kaf24/), and Hakan Sundell's thesis
     * (http://www.cs.chalmers.se/~phs/).
     *
     * Given the use of tree-like index nodes, you might wonder why this doesn't
     * use some kind of search tree instead, which would support somewhat faster
     * search operations. The reason is that there are no known efficient
     * lock-free insertion and deletion algorithms for search trees. The
     * immutability of the "down" links of index nodes (as opposed to mutable
     * "left" fields in true trees) makes this tractable using only CAS
     * operations.
     *
     * Notation guide for local variables Node: b, n, f for predecessor, node,
     * successor Index: q, r, d for index node, right, down. t for another index
     * node Head: h Levels: j Keys: k, key Values: v, value Comparisons: c
     */

    private static final int EMPTY = Integer.MIN_VALUE;

    private static final long serialVersionUID = -8627078645895051609L;


    private static final Random seedGenerator = new Random();


    private static final Object BASE_HEADER = new Object();


    private transient volatile HeadIndex head;

    private transient int randomSeed;

    final void initialize() {
        randomSeed = seedGenerator.nextInt() | 0x0100; // ensure nonzero
        head = new HeadIndex(new Node(EMPTY, BASE_HEADER, null),
                null, null, 1);
    }


    private static final AtomicReferenceFieldUpdater<ConcurrentSkipListSetInt, HeadIndex> headUpdater = AtomicReferenceFieldUpdater
            .newUpdater(ConcurrentSkipListSetInt.class, HeadIndex.class,
                    "head");


    private boolean casHead(HeadIndex cmp, HeadIndex val) {
        return headUpdater.compareAndSet(this, cmp, val);
    }

    /* ---------------- Nodes -------------- */


    static final class Node {
        final int key;
        volatile Object value;
        volatile Node next;


        Node(int key, Object value, Node next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }


        Node(Node next) {
            this.key = EMPTY;
            this.value = this;
            this.next = next;
        }


        static final AtomicReferenceFieldUpdater<Node, Node> nextUpdater = AtomicReferenceFieldUpdater
                .newUpdater(Node.class, Node.class, "next");


        static final AtomicReferenceFieldUpdater<Node, Object> valueUpdater = AtomicReferenceFieldUpdater
                .newUpdater(Node.class, Object.class, "value");


        boolean casValue(Object cmp, Object val) {
            return valueUpdater.compareAndSet(this, cmp, val);
        }


        boolean casNext(Node cmp, Node val) {
            return nextUpdater.compareAndSet(this, cmp, val);
        }


        boolean isMarker() {
            return value == this;
        }


        boolean isBaseHeader() {
            return value == BASE_HEADER;
        }


        boolean appendMarker(Node f) {
            return casNext(f, new Node(f));
        }


        void helpDelete(Node b, Node f) {
            /*
             * Rechecking links and then doing only one of the help-out stages
             * per call tends to minimize CAS interference among helping
             * threads.
             */
            if (f == next && this == b.next) {
                if (f == null || f.value != f) // not already marked
                    appendMarker(f);
                else
                    b.casNext(this, f.next);
            }
        }


        int getValidKey() {
            Object v = value;
            if (v == this || v == BASE_HEADER)
                return EMPTY;
            return key;
        }
    }

    /* ---------------- Indexing -------------- */


    static class Index {
        final Node node;
        final Index down;
        volatile Index right;


        Index(Node node, Index down, Index right) {
            this.node = node;
            this.down = down;
            this.right = right;
        }


        static final AtomicReferenceFieldUpdater<Index, Index> rightUpdater = AtomicReferenceFieldUpdater
                .newUpdater(Index.class, Index.class, "right");


        final boolean casRight(Index cmp, Index val) {
            return rightUpdater.compareAndSet(this, cmp, val);
        }


        final boolean indexesDeletedNode() {
            return node.value == null;
        }


        final boolean link(Index succ, Index newSucc) {
            Node n = node;
            newSucc.right = succ;
            return n.value != null && casRight(succ, newSucc);
        }


        final boolean unlink(Index succ) {
            // if(STRUCT_MODS)
            // counts.get().structMods++;
            return !indexesDeletedNode() && casRight(succ, succ.right);
        }
    }

    /* ---------------- Head nodes -------------- */


    static final class HeadIndex extends Index {
        final int level;

        HeadIndex(Node node, Index down, Index right,
                  int level) {
            super(node, down, right);
            this.level = level;
        }
    }

    /* ---------------- Comparison utilities -------------- */

    /* ---------------- Traversal -------------- */


    private Node findPredecessor(int key) {
        for (; ; ) {
            Index q = head;
            Index r = q.right;
            for (; ; ) {
                if (r != null) {
                    Node n = r.node;
                    int k = n.key;
                    if (n.value == null) {
                        if (!q.unlink(r))
                            break; // restart
                        r = q.right; // reread r
                        continue;
                    }
                    if (key > k) {
                        q = r;
                        r = r.right;
                        continue;
                    }
                }
                Index d = q.down;
                if (d != null) {
                    q = d;
                    r = d.right;
                } else
                    return q.node;
            }
        }
    }


    private Node findNode(int key) {
        for (; ; ) {
            Node b = findPredecessor(key);
            Node n = b.next;
            for (; ; ) {
                if (n == null)
                    return null;
                Node f = n.next;
                if (n != b.next) // inconsistent read
                    break;
                Object v = n.value;
                if (v == null) { // n is deleted
                    n.helpDelete(b, f);
                    break;
                }
                if (v == n || b.value == null) // b is deleted
                    break;
                int c = key - n.key;
                if (c == 0)
                    return n;
                if (c < 0)
                    return null;
                b = n;
                n = f;
            }
        }
    }

    // Extra version so we count the get traversals
    private Node findGetNode(int key) {
        for (; ; ) {
            Node b = findPredecessor(key);
            Node n = b.next;
            for (; ; ) {
                if (n == null) {
                    return null;
                }
                Node f = n.next;
                if (n != b.next) // inconsistent read
                    break;
                Object v = n.value;
                if (v == null) { // n is deleted
                    n.helpDelete(b, f);
                    break;
                }
                if (v == n || b.value == null) // b is deleted
                    break;
                int c = key - n.key;
                if (c == 0) {
                    return n;
                }
                if (c < 0) {
                    return null;
                }
                b = n;
                n = f;
            }
        }
    }


    private Boolean doGet(Object okey) {
        int key = (Integer) okey;
        Node bound = null;
        Index q = head;
        Index r = q.right;
        Node n;
        int k;
        int c;

        for (; ; ) {
            Index d;
            // Traverse rights
            if (r != null && (n = r.node) != bound && (k = n.key) != EMPTY) {
                if ((c = key - k) > 0) {
                    q = r;
                    r = r.right;
                    continue;
                } else if (c == 0) {
                    Object v = n.value;
                    return (v != null) ? (Boolean) v : getUsingFindNode(key);
                } else
                    bound = n;
            }

            // Traverse down
            if ((d = q.down) != null) {
                q = d;
                r = d.right;
            } else
                break;
        }

        // Traverse nexts
        for (n = q.node.next; n != null; n = n.next) {
            if ((k = n.key) != EMPTY) {
                if ((c = key - k) == 0) {
                    Object v = n.value;
                    return (v != null) ? (Boolean) v : getUsingFindNode(key);
                } else if (c < 0)
                    break;
            }
        }
        return null;
    }


    private Boolean getUsingFindNode(int key) {
        /*
         * Loop needed here and elsewhere in case value field goes null just as
         * it is about to be returned, in which case we lost a race with a
         * deletion, so must retry.
         */
        for (; ; ) {
            Node n = findGetNode(key);
            if (n == null)
                return null;
            Object v = n.value;
            if (v != null)
                return (Boolean) v;
        }
    }

    /* ---------------- Insertion -------------- */


    private boolean doPut(int key, Boolean value, boolean onlyIfAbsent) {
        for (; ; ) {
            Node b = findPredecessor(key);
            Node n = b.next;
            for (; ; ) {
                if (n != null) {
                    Node f = n.next;
                    if (n != b.next) // inconsistent read
                        break;
                    ;
                    Object v = n.value;
                    if (v == null) { // n is deleted
                        n.helpDelete(b, f);
                        break;
                    }
                    if (v == n || b.value == null) // b is deleted
                        break;
                    int c = key - n.key;
                    if (c > 0) {
                        b = n;
                        n = f;
                        continue;
                    }
                    if (c == 0) {
                        if (onlyIfAbsent || n.casValue(v, value))
                            return false;
                        else
                            break; // restart if lost race to replace value
                    }
                    // else c < 0; fall through
                }

                Node z = new Node(key, value, n);
                if (!b.casNext(n, z))
                    break; // restart if lost race to append to b
                int level = randomLevel();
                if (level > 0) {
                    // if(STRUCT_MODS)
                    // counts.get().structMods += level - 1;
                    insertIndex(z, level);
                }
                return true;
            }
        }
    }


    private int randomLevel() {
        int x = randomSeed;
        x ^= x << 13;
        x ^= x >>> 17;
        randomSeed = x ^= x << 5;
        if ((x & 0x8001) != 0) // test highest and lowest bits
            return 0;
        int level = 1;
        while (((x >>>= 1) & 1) != 0)
            ++level;
        return level;
    }


    private void insertIndex(Node z, int level) {
        HeadIndex h = head;
        int max = h.level;

        if (level <= max) {
            Index idx = null;
            for (int i = 1; i <= level; ++i)
                idx = new Index(z, idx, null);
            addIndex(idx, h, level);

        } else { // Add a new level
            /*
             * To reduce interference by other threads checking for empty levels
             * in tryReduceLevel, new levels are added with initialized right
             * pointers. Which in turn requires keeping levels in an array to
             * access them while creating new head index nodes from the opposite
             * direction.
             */
            level = max + 1;
            Index[] idxs = (Index[]) new Index[level + 1];
            Index idx = null;
            for (int i = 1; i <= level; ++i)
                idxs[i] = idx = new Index(z, idx, null);

            HeadIndex oldh;
            int k;
            for (; ; ) {
                oldh = head;
                int oldLevel = oldh.level;
                if (level <= oldLevel) { // lost race to add level
                    k = level;
                    break;
                }
                HeadIndex newh = oldh;
                Node oldbase = oldh.node;
                for (int j = oldLevel + 1; j <= level; ++j)
                    newh = new HeadIndex(oldbase, newh, idxs[j], j);
                if (casHead(oldh, newh)) {
                    k = oldLevel;
                    break;
                }
            }
            addIndex(idxs[k], oldh, k);
        }
    }


    private void addIndex(Index idx, HeadIndex h, int indexLevel) {
        // Track next level to insert in case of retries
        int insertionLevel = indexLevel;
        int key = (idx.node.key);
        // Similar to findPredecessor, but adding index nodes along
        // path to key.
        for (; ; ) {
            int j = h.level;
            Index q = h;
            Index r = q.right;
            Index t = idx;
            for (; ; ) {
                if (r != null) {
                    Node n = r.node;
                    // compare before deletion check avoids needing recheck
                    int c = key - n.key;
                    if (n.value == null) {
                        if (!q.unlink(r))
                            break;
                        r = q.right;
                        continue;
                    }
                    if (c > 0) {
                        q = r;
                        r = r.right;
                        continue;
                    }
                }

                if (j == insertionLevel) {
                    // Don't insert index if node already deleted
                    if (t.indexesDeletedNode()) {
                        findNode(key); // cleans up
                        return;
                    }
                    if (!q.link(r, t))
                        break; // restart
                    if (--insertionLevel == 0) {
                        // need final deletion check before return
                        if (t.indexesDeletedNode())
                            findNode(key);
                        return;
                    }
                }

                if (--j >= insertionLevel && j < indexLevel)
                    t = t.down;
                q = q.down;
                r = q.right;
            }
        }
    }

    /* ---------------- Deletion -------------- */


    final boolean doRemove(Object okey) {
        int key = (Integer) okey;
        for (; ; ) {
            Node b = findPredecessor(key);
            Node n = b.next;
            for (; ; ) {
                if (n == null)
                    return false;
                Node f = n.next;
                if (n != b.next) // inconsistent read
                    break;
                Object v = n.value;
                if (v == null) { // n is deleted
                    n.helpDelete(b, f);
                    break;
                }
                if (v == n || b.value == null) // b is deleted
                    break;
                int c = key - n.key;
                if (c < 0)
                    return false;
                if (c > 0) {
                    b = n;
                    n = f;
                    continue;
                }
                if (!n.casValue(v, null))
                    break;
                if (!n.appendMarker(f) || !b.casNext(n, f))
                    findNode(key); // Retry via findNode
                else {
                    findPredecessor(key); // Clean index
                    if (head.right == null)
                        tryReduceLevel();
                }
                return true;
            }
        }
    }


    private void tryReduceLevel() {
        HeadIndex h = head;
        HeadIndex d;
        HeadIndex e;
        if (h.level > 3 && (d = (HeadIndex) h.down) != null
                && (e = (HeadIndex) d.down) != null && e.right == null
                && d.right == null && h.right == null && casHead(h, d) && // try
                // to
                // set
                h.right != null) // recheck
            casHead(d, h); // try to backout
    }

    /* ---------------- Finding and removing first element -------------- */


    Node findFirst() {
        for (; ; ) {
            Node b = head.node;
            Node n = b.next;
            if (n == null)
                return null;
            if (n.value != null)
                return n;
            n.helpDelete(b, n.next);
        }
    }

    private void clearIndexToFirst() {
        for (; ; ) {
            Index q = head;
            for (; ; ) {
                Index r = q.right;
                if (r != null && r.indexesDeletedNode() && !q.unlink(r))
                    break;
                if ((q = q.down) == null) {
                    if (head.right == null)
                        tryReduceLevel();
                    return;
                }
            }
        }
    }

    /* ---------------- Finding and removing last element -------------- */


    Node findLast() {
        /*
         * findPredecessor can't be used to traverse index level because this
         * doesn't use comparisons. So traversals of both levels are folded
         * together.
         */
        Index q = head;
        for (; ; ) {
            Index d, r;
            if ((r = q.right) != null) {
                if (r.indexesDeletedNode()) {
                    q.unlink(r);
                    q = head; // restart
                } else
                    q = r;
            } else if ((d = q.down) != null) {
                q = d;
            } else {
                Node b = q.node;
                Node n = b.next;
                for (; ; ) {
                    if (n == null)
                        return (b.isBaseHeader()) ? null : b;
                    Node f = n.next; // inconsistent read
                    if (n != b.next)
                        break;
                    Object v = n.value;
                    if (v == null) { // n is deleted
                        n.helpDelete(b, f);
                        break;
                    }
                    if (v == n || b.value == null) // b is deleted
                        break;
                    b = n;
                    n = f;
                }
                q = head; // restart
            }
        }
    }
    /* ---------------- Relational operations -------------- */

    // Control values OR'ed as arguments to findNear
    private static final int EQ = 1;
    private static final int LT = 2;
    private static final int GT = 0; // Actually checked as !LT

    /* ---------------- Constructors -------------- */


    public ConcurrentSkipListSetInt() {
        initialize();
    }

	@Override
	public Iterator<Integer> iterator() {
		return null;
	}

	/* ------ Set API methods ------ */


    public boolean contains(Object key) {
        return doGet(key) != null;
    }

    public boolean add(Integer key) {
        return doPut(key, Boolean.TRUE, false);
    }


    public boolean remove(Object key) {
        return doRemove(key);
    }

    public int size() {
        long count = 0;
        for (Node n = findFirst(); n != null; n = n.next) {
            if (n.getValidKey() != EMPTY)
                ++count;
        }
        return (count >= Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) count;
    }


    public boolean isEmpty() {
        return findFirst() == null;
    }


    public void clear() {
        initialize();
    }

    /* ---------------- View methods -------------- */

    /*
     * Note: Lazy initialization works for views because view classes are
     * stateless/immutable so it doesn't matter wrt correctness if more than one
     * is created (which will only rarely happen). Even so, the following idiom
     * conservatively ensures that the method returns the one it created if it
     * does so, not one created by another racing thread.
     */

    /* ------ ConcurrentMap API methods ------ */

    /* ---------------- Relational operations -------------- */

    /* ---------------- Iterators -------------- */

    // Factory methods for iterators needed by ConcurrentSkipListSet etc

    /* ---------------- View Classes -------------- */

    /*
     * View classes are static, delegating to a ConcurrentNavigableMap to allow
     * use by SubMaps, which outweighs the ugliness of needing type-tests for
     * Iterator methods.
     */

    static final <E> List<E> toList(Collection<E> c) {
        // Using size() here would be a pessimization.
        List<E> list = new ArrayList<E>();
        for (E e : c)
            list.add(e);
        return list;
    }
}
