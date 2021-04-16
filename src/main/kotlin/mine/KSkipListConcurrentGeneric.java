package mine;

import kotlin.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static kotlin.TuplesKt.to;

public class KSkipListConcurrentGeneric<E> extends AbstractSet<E> {
    private final int k;
    private final int andK;
    private final Comparator<E> comparator;
    private static final int MAX_HEIGHT = 32;

    private boolean sift() {
        return ThreadLocalRandom.current().nextDouble() < 0.5;
    }

    private final AtomicInteger maxLevel = new AtomicInteger(0);

    private final E EMPTY = null;
    private final Node tail;
    private final Node head;


    public KSkipListConcurrentGeneric() {
        this(32);
    }

    public KSkipListConcurrentGeneric(int k) {
        this(k, null);
    }

    public KSkipListConcurrentGeneric(int k, Comparator<E> comp) {
        this.k = k;
        this.andK = k - 1;
        comparator = comp;
        tail = new Node();
        head = new Node();
        head.next.add(tail);
        tail.next.add(tail);
    }

    @Override
    @NotNull
    public Iterator<E> iterator() {
        return toList().iterator();
    }

    public List<E> toList() {
        List<E> arrayList = new ArrayList<E>();
        Node cur = head;
        while (cur != tail) {
            for (int it = 0; it < k; it++) {
                if (cur.array.key.get(it) != EMPTY) {
                    arrayList.add(cur.array.key.get(it));
                }
            }
            cur = cur.next.get(0);
        }
        return Collections.unmodifiableList(arrayList);
    }

    @Override
    public int size() {
        int size = 0;
        Node cur = head;
        while (cur != tail) {
            for (int it = 0; it < k; it++) {
                if (cur.array.key.get(it) != EMPTY) {
                    size++;
                }
            }
            cur = cur.next.get(0);
        }
        return size;
    }

    private AtomicReferenceArray<E> kArray() {
        AtomicReferenceArray<E> atomicIntegerArray = new AtomicReferenceArray<E>(k);
        for (int it = 0; it < k; it++) {
            atomicIntegerArray.set(it, EMPTY);
        }
        return atomicIntegerArray;
    }

    class Vector {
        volatile AtomicReferenceArray<Node> values;

        public void add(Node v) {
            AtomicReferenceArray<Node> newAr = new AtomicReferenceArray<>(values.length() + 1);
            for (int i = 0; i < values.length(); i++) {
                newAr.set(i, values.get(i));
            }
            newAr.set(values.length(), v);
            values = newAr;
        }

        void set(int i, Node v) {
            values.set(i, v);
        }


        Node get(int i) {
            return values.get(i);
        }

        int size() {
            return values.length();
        }

        Vector() {
            values = new AtomicReferenceArray<>(0);
        }
    }

    class NodeArray {
        final AtomicReferenceArray<E> key;
        volatile int end;

        public NodeArray() {
            this.key = kArray();
            this.end = 0;
        }

        public NodeArray(AtomicReferenceArray<E> kArray, int end) {
            this.key = kArray;
            this.end = end;
        }
    }

    class Node {

//        volatile AtomicReferenceArray<E> key = kArray();
//        volatile int end = 0;
        volatile NodeArray array = new NodeArray();
//        volatile Pair<Integer, >
        volatile E initialMin = EMPTY;

        final Vector next = new Vector();
        final Lock lock = new ReentrantLock();

        volatile CopyOnWriteArrayList<Node> deletedBy = new CopyOnWriteArrayList<>();
        volatile boolean deleted = false;

        Pair<Integer, Integer> posOfVAndEmpty(Comparable<? super E> v) {
            int end = array.end;
            if (end == 0) {
                return to(-1, -1);
            } else {
                int posV = -1;
                int i = 0;
                while (i != end) {
                    E cur = array.key.get(i);
                    if (v.compareTo(cur) == 0) {
                        posV = i;
                        break;
                    }
                    i++;
                }
                return to(posV, k == end ? -1 : end);
            }
        }

        void lock() {
            lock.lock();
        }

        void unlock() {
            lock.unlock();
        }

        boolean has(Comparable<? super E> v) {
            NodeArray singleReadArray = this.array;
            AtomicReferenceArray<E> singleReadKey = singleReadArray.key;
            if (deleted)
                return false;
            for (int it = singleReadArray.end - 1; it >= 0; it--) {
                E cur = singleReadKey.get(it);
                if (cur != EMPTY && v.compareTo(cur) == 0) {
                    return true;
                }
            }
            return false;
        }


        boolean remove(E v) {
            int end = array.end;
            AtomicReferenceArray<E> key = array.key;
            if (end == 0) {
                return false;
            } else {
                int posV = -1;
                int i = 0;
                while (i != end) {
                    E cur = key.get(i);
                    if (cpr(cur, v) == 0) {
                        posV = i;
                        break;
                    }
                    i++;
                }
                if (posV != -1) {
                    key.set(posV, key.get(end - 1));
                    key.set(end - 1, EMPTY);
                    array.end--;
                    deleted = end == 1;
                    return true;
                } else {
                    return false;
                }
            }
        }
    }

    private Node firstNotPhysicallyDeleted(Node curInp, int level) {
        Node cur = curInp;
        while (cur.deleted && cur.deletedBy.size() > level) {
            Node next = cur.deletedBy.get(level);
            cur.unlock();

            cur = next;
            cur.lock();
        }
        return cur;
    }

    private Node moveForwardBlocking(Node curInp, int level, Comparable<? super E> v) {
        Node cur = firstNotPhysicallyDeleted(curInp, level);
        Node next = cur.next.get(level);

        next.lock();
        while (next != tail && v.compareTo(next.initialMin) >= 0) {
            cur.unlock();
            cur = next;
            next = next.next.get(level);
            next.lock();
        }
        next.unlock();

        return cur;
    }

    private Pair<Node, List<Node>> findAllCandidates(Comparable<? super E> v) {
        Node cur = head;
        int curDepth = maxLevel.get();
        List<Node> path = new ArrayList<>(curDepth + 2);
        for (int i = 0; i < curDepth + 1; i++) {
            path.add(null);
        }
        while (curDepth >= 0) {
            Node next = cur.next.get(curDepth);
            while (next != tail && v.compareTo(next.initialMin) >= 0) {
                cur = next;
                next = cur.next.get(curDepth);
            }
            path.set(curDepth, cur);
            curDepth--;
        }
        return to(cur, path);
    }

    private Pair<Node, List<Node>> findAllPrevCandidates(E v) {
        Node prev = head;
        int curDepth = maxLevel.get();
        List<Node> path = new ArrayList<>(curDepth + 2);
        for (int i = 0; i < curDepth + 1; i++) {
            path.add(null);
        }
        while (curDepth >= 0) {
            Node cur = prev;
            Node next = cur.next.get(curDepth);
            while (next.deleted) {
                next = next.next.get(curDepth);
            }
            while (next != tail && cpr(next.initialMin, v) <= 0) {
                prev = cur;
                cur = next;
                next = cur.next.get(curDepth);
                while (next.deleted) {
                    next = next.next.get(curDepth);
                }
            }
            path.set(curDepth, prev);
            curDepth--;
        }
        return to(prev, path);
    }

    @Override
    public boolean add(E element1) {
        Comparable<? super E> element = comparable(element1);
        Pair<Node, List<Node>> allCandidates = findAllCandidates(element);
        Node cur = allCandidates.component1();
        List<Node> path = allCandidates.component2();
        cur.lock();

        cur = moveForwardBlocking(cur, 0, element);
        Node newNode = null;

        if (cur == head) {
            newNode = new Node();
            newNode.lock();
            newNode.array.key.set(0, element1);
            newNode.initialMin = element1;
            newNode.array.end++;
            newNode.next.add(cur.next.get(0));
            cur.next.set(0, newNode);
            newNode.unlock();
            cur.unlock();
        } else {
            Pair<Integer, Integer> posOfVAndEmpty = cur.posOfVAndEmpty(element);
            int vPos = posOfVAndEmpty.component1();
            int emptyPos = posOfVAndEmpty.component2();
            if (vPos != -1) {
                cur.unlock();
                return false;
            } else {
                if (emptyPos == -1) {
                    newNode = new Node();
                    //splitting array between nodes

                    int l = 0;
                    int r = 0;
                    AtomicReferenceArray<E> kArrayL = kArray();
                    for (int i = 0; i < k; i++) {
                        E curValue = cur.array.key.get(i);
                        if (element.compareTo(curValue) < 0) { //TODO(почему не пополам)
                            newNode.array.key.set(r++, curValue);
                        } else {
                            kArrayL.set(l++, curValue);
                        }
                    }
                    if (r == k) {
                        kArrayL.set(l++, element1);
                        E newMin = null;
                        for (int i = 0; i < k; i++) {
                            if (newMin == null) {
                                newMin = newNode.array.key.get(i);
                            } else {
                                if (cpr(newNode.array.key.get(i), newMin) >= 0) {
                                    newMin = newMin;
                                } else {
                                    newMin = newNode.array.key.get(i);
                                }
                            }
                        }
                        newNode.initialMin = newMin;
                    } else {
                        newNode.array.key.set(r++, element1);
                        newNode.initialMin = element1;
                    }
                    newNode.array.end = r;

                    newNode.next.add(cur.next.get(0));
                    newNode.lock();
                    cur.next.set(0, newNode);
                    cur.array = new NodeArray(kArrayL, l);
                    newNode.unlock();
                    cur.unlock();
                } else {
                    cur.array.key.set(emptyPos, element1);
                    cur.array.end++;
                    cur.unlock();
                    return true;
                }
            }
        }
        if (newNode != null) {
            int currentMaxLevel = maxLevel.get();
            int heightForNode = 0;
            while (heightForNode <= currentMaxLevel && heightForNode < MAX_HEIGHT && sift()) {
                heightForNode++;
            }

            for (int curLevel = 1; curLevel <= heightForNode; curLevel++) {
                cur = curLevel >= path.size() ? head : path.get(curLevel);
                cur.lock();
                if (cur.next.size() <= curLevel) {
                    cur.next.add(tail);
                    maxLevel.incrementAndGet();
                }
                cur = moveForwardBlocking(cur, curLevel, comparable(newNode.initialMin));
                newNode.lock();

                if (newNode.deleted) {
                    newNode.unlock();
                    cur.unlock();
                    return true;
                }
                newNode.next.add(cur.next.get(curLevel));
                cur.next.set(curLevel, newNode);
                newNode.unlock();
                cur.unlock();
            }
        }
        return true;
    }

    @Override
    public boolean remove(Object element1) {
        E element = (E) element1;
        Pair<Node, List<Node>> allPrevCandidates = findAllPrevCandidates(element);
        Node cur = allPrevCandidates.component1();
        List<Node> path = allPrevCandidates.component2();
        int curLevel = 0;
        cur.lock();
        cur = firstNotPhysicallyDeleted(cur, curLevel);
        Node next = cur.next.get(curLevel);

        next.lock();
        if (next == tail || cpr(next.initialMin, element) >= 1) {
            cur.unlock();
            next.unlock();
            return false;
        }
        Node nextNext = next.next.get(curLevel);
//        nextNext.lock();
        while (nextNext != tail && cpr(nextNext.initialMin, element) <= 0) {
            cur.unlock();
            cur = next;
            next = nextNext;
            next.lock();
            nextNext = nextNext.next.get(curLevel);
//            nextNext.lock();
        }
//        nextNext.unlock();
        boolean removeRes = next.remove(element);

        if (next.deleted) {
            cur.next.set(curLevel, nextNext);
            next.deletedBy.add(cur);
            Node forDelete = next;
            int height = forDelete.next.size();
            next.unlock();
            cur.unlock();
            curLevel++;
            while (curLevel < height) {
                cur = curLevel < path.size() ? path.get(curLevel) : head;
                cur.lock();
                cur = firstNotPhysicallyDeleted(cur, curLevel);
                next = cur.next.get(curLevel);
                while (next != forDelete && next != tail && cpr(next.initialMin, forDelete.initialMin) <= 0) {
                    next.lock();
                    cur.unlock();
                    cur = next;
                    next = next.next.get(curLevel);
                }
                if (next == tail || cpr(next.initialMin, forDelete.initialMin) >= 1) {
                    cur.unlock();
                    curLevel++;
                    continue;
                }

                forDelete.lock();
                cur.next.set(curLevel, forDelete.next.get(curLevel));
                forDelete.deletedBy.add(cur);
                forDelete.unlock();
                cur.unlock();
                curLevel++;
            }
            return true;
        } else {
            next.unlock();
            cur.unlock();
            return removeRes;
        }
    }

    private Node find(Comparable<? super E> v) {
        Node cur = head;
        int curDepth = maxLevel.get();
        while (curDepth >= 0) {
            Node next = cur.next.get(curDepth);
            while (next.deleted) {
                next = next.next.get(curDepth);
            }
            while (next != tail && v.compareTo(next.initialMin) >= 0) {
                cur = next;
                next = next.next.get(curDepth);
                while (next.deleted) {
                    next = next.next.get(curDepth);
                }
            }
            curDepth--;
        }
        return cur;
    }

    @Override
    public boolean contains(Object element1) {
        Comparable<? super E> element = comparable(element1);
        Node cur = find(element);
        do {
            if (cur.has(element)) {
                return true;
            }
            cur = cur.next.get(0);
        } while (cur != tail && element.compareTo(cur.initialMin) >= 0);
        return false;
    }

    static final class ComparableUsingComparator<K> implements Comparable<K> {
        final K actualKey;
        final Comparator<? super K> cmp;

        ComparableUsingComparator(K key, Comparator<? super K> cmp) {
            this.actualKey = key;
            this.cmp = cmp;
        }

        public int compareTo(K k2) {
            return cmp.compare(actualKey, k2);
        }
    }

    private Comparable<? super E> comparable(Object key) {
        if (comparator != null)
            return new ComparableUsingComparator<>((E) key, comparator);
        else
            return (Comparable<? super E>) key;
    }

    int cpr(E x, E y) {
        return comparator != null ? comparator.compare(x, y) : ((Comparable<E>) x).compareTo(y);
    }
}
