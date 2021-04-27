package mine;

import mine.util.Statistic;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class KSkipListConcurrentInt extends AbstractSet<Integer> {
    private final int k;
    private static final int MAX_HEIGHT = 32;
    public static final ThreadLocal<Statistic> stat = ThreadLocal.withInitial(Statistic::new);

    private boolean sift() {
        return ThreadLocalRandom.current().nextDouble() < 0.5;
    }

    private final AtomicInteger maxLevel = new AtomicInteger(0);

    private final int EMPTY = Integer.MIN_VALUE;
    private final Node tail;
    private final Node head;


    public KSkipListConcurrentInt() {
        this(32);
    }

    public KSkipListConcurrentInt(int k) {
        this.k = k;
        tail = new Node();
        head = new Node();
        head.next.add(tail);
        tail.next.add(tail);
    }

    @Override
    @NotNull
    public Iterator<Integer> iterator() {
        return toList().iterator();
    }

    public List<Integer> toList() {
        List<Integer> arrayList = new ArrayList<Integer>();
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

    private AtomicIntegerArray kArray() {
        AtomicIntegerArray array = new AtomicIntegerArray(k);
        for (int it = 0; it < k; it++) {
            array.setPlain(it, EMPTY);
        }
        return array;
    }

    class NodeArray {
        final AtomicIntegerArray key;
        volatile int end;

        public NodeArray() {
            this.key = kArray();
            this.end = 0;
        }

        public NodeArray(AtomicIntegerArray kArray, int end) {
            this.key = kArray;
            this.end = end;
        }
    }

    static class SpinLock {
        AtomicBoolean lock = new AtomicBoolean(false);

        public void lock() {
            while (!lock.compareAndSet(false, true)) {
            }
        }

        public void unlock() {
            lock.set(false);
        }
    }

    class Node {

        volatile NodeArray array = new NodeArray();
        volatile int initialMin = EMPTY;

        final CopyOnWriteArrayList<Node> next = new CopyOnWriteArrayList<>();
        final SpinLock lock = new SpinLock();
//        final ReentrantLock lock = new ReentrantLock();

        ArrayList<Node> deletedBy = new ArrayList<>();
        volatile boolean deleted = false;

        int posOfV(int v) {
            int end = array.end;
            if (end == 0) {
                return -1;
            } else {
                AtomicIntegerArray key = array.key;
                int i = 0;
                while (i != end) {
                    int cur = key.getPlain(i);
                    if (v == cur) {
                        return i;
                    }
                    i++;
                }
            }
            return -1;
        }

        void lock() {
            lock.lock();
        }

        void unlock() {
            lock.unlock();
        }

        boolean has(int v) {
            if (deleted)
                return false;
            NodeArray singleReadArray = this.array;
            AtomicIntegerArray singleReadKey = singleReadArray.key;
            for (int it = singleReadArray.end - 1; it >= 0; it--) {
                int cur = singleReadKey.getPlain(it);
                if (cur != EMPTY && v == cur) {
                    return true;
                }
            }
            return false;
        }


        boolean remove(int v) {
            int end = array.end;
            AtomicIntegerArray key = array.key;
            if (end == 0) {
                return false;
            } else {
                int posV = -1;
                int i = 0;
                while (i != end) {
                    int cur = key.getPlain(i);
                    if (v == cur) {
                        posV = i;
                        break;
                    }
                    i++;
                }
                if (posV != -1) {
                    key.setPlain(posV, key.getPlain(end - 1));
                    key.set(end - 1, EMPTY);
                    array.end = end - 1;
                    deleted = end == 1;
                    return true;
                } else {
                    return false;
                }
            }
        }

        private String toArr() {
            StringBuilder sb = new StringBuilder();
            AtomicIntegerArray key = array.key;
            sb.append(key.get(0));
            for (int i = 1; i < k; i++) {
                sb.append(",");
                sb.append(key.get(i));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "|min:" + initialMin + "; del:" + deleted + ";ar:" + toArr() + "|";
        }
    }

    private Node firstNotPhysicallyDeleted(Node curInp, int level) { //TODO count successful calls
        Node cur = curInp;
        int height = cur.next.size();
        int notDeletedLevels = height - cur.deletedBy.size();
//        stat.get().findNotDeletedCalls++;
        while (cur.deleted && notDeletedLevels - 1 < level) {
//            stat.get().deletedNodesTraversed++;
            Node next = cur.deletedBy.get(height - level - 1);
            cur.unlock();

            cur = next;
            cur.lock();
            height = cur.next.size();
            notDeletedLevels = height - cur.deletedBy.size();
        }
        return cur;
    }

    private Node moveForwardBlocking(Node curInp, int level, int v) { //TODO count successful calls
        Node cur = firstNotPhysicallyDeleted(curInp, level);
        Node next = cur.next.get(level);
//        stat.get().moveForwardRequests++;
        while (next != tail && v >= next.initialMin) {
//            stat.get().nodesMovedForward++;
            next.lock();
            cur.unlock();
            cur = next;
            next = next.next.get(level);
        }

        return cur;
    }

    @Override
    public boolean add(Integer element) {
        Node cur = find(element);
        Node newNode;
        cur.lock();

        cur = moveForwardBlocking(cur, 0, element);

        if (cur == head || cur.deleted) {
            newNode = new Node();
            newNode.array.key.setPlain(0, element);
            newNode.initialMin = element;
            newNode.array.end++;
            newNode.next.add(cur.next.get(0));
            cur.next.set(0, newNode);
            cur.unlock();
        } else {
            int vPos = cur.posOfV(element);
            if (vPos != -1) {
                cur.unlock();
                return false;
            } else {
                int end = cur.array.end;
                int emptyPos = end == k ? -1 : end;
                if (emptyPos == -1) {
                    newNode = new Node();
                    //splitting array between nodes

                    int l = 0;
                    int r = 0;
                    AtomicIntegerArray kArrayL = kArray();
                    AtomicIntegerArray newNodeKey = newNode.array.key;
                    AtomicIntegerArray oldNodeKey = cur.array.key;
                    for (int i = 0; i < k; i++) {
                        int curValue = oldNodeKey.getPlain(i);
                        if (element < curValue) {
                            newNodeKey.setPlain(r++, curValue);
                        } else {
                            kArrayL.setPlain(l++, curValue);
                        }
                    }
                    if (r == k) {
                        kArrayL.setPlain(l++, element);
                        int newMin = EMPTY;
                        for (int i = 0; i < k; i++) {
                            if (newMin == EMPTY) {
                                newMin = newNodeKey.getPlain(i);
                            } else {
                                if (newNodeKey.getPlain(i) >= newMin) {
                                    newMin = newMin;
                                } else {
                                    newMin = newNodeKey.getPlain(i);
                                }
                            }
                        }
                        newNode.initialMin = newMin;
                    } else {
                        newNodeKey.set(r++, element);
                        newNode.initialMin = element;
                    }
                    newNode.array.end = r;

                    newNode.next.add(cur.next.get(0));
                    cur.next.set(0, newNode);
                    cur.array = new NodeArray(kArrayL, l);
                    cur.unlock();
                } else {
                    cur.array.key.setPlain(emptyPos, element);
                    cur.array.end++;
                    cur.unlock();
//                    stat.get().successfulOptimisticAdds++;
                    return true;
                }
            }
        }
        //newNode was created
        int heightForNode = 0;
        while (heightForNode < MAX_HEIGHT && sift()) {
            heightForNode++;
        }
        if (heightForNode == 0) {
            return true;
        }
//        stat.get().failedOptimisticAdds++;
        List<Node> path = findAllCandidates(newNode, heightForNode + 1);
        for (int curLevel = 1; curLevel <= heightForNode; curLevel++) {
//                cur = curLevel >= path.size() ? head : path.get(curLevel);
            cur = path.get(curLevel) == null ? head : path.get(curLevel);
            cur.lock();
            if (cur.next.size() <= curLevel) {
                cur.next.add(tail);
                maxLevel.incrementAndGet();
            }
            cur = moveForwardBlocking(cur, curLevel, newNode.initialMin);
            newNode.lock();

            //someone already deleted that node
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
        return true;
    }

    @Override
    public boolean remove(Object element1) {
        int element = (Integer) element1;
        //OPTIMISTIC REMOVE //TODO count successful
        Node find = find(element);
        find.lock();
        Node cur = moveForwardBlocking(find, 0, element);
        if (cur.deleted) {
            cur.unlock();
            return false;
        }
        boolean optimisticRemoveRes = cur.remove(element);
        boolean deleted = cur.deleted;
        cur.unlock();
        if (optimisticRemoveRes) {
            if (deleted) {
//                stat.get().failedOptimisticRemoves++;
                //need physically remove node
                List<Node> path = findAllPrevs(cur);
                Node forDelete = cur;
                int curLevel = forDelete.next.size() - 1;
                while (curLevel >= 0) {
                    cur = path.get(curLevel);
                    cur.lock();
                    cur = firstNotPhysicallyDeleted(cur, curLevel);
                    Node next = cur.next.get(curLevel);
                    while (next != forDelete) {
                        next.lock();
                        cur.unlock();
                        cur = next;
                        next = next.next.get(curLevel);
                    }
                    forDelete.lock();
                    cur.next.set(curLevel, forDelete.next.get(curLevel));
                    forDelete.deletedBy.add(cur);
                    forDelete.unlock();
                    cur.unlock();
                    curLevel--;
                }
            } /*else {
                stat.get().successfulOptimisticRemoves++;
            }*/
            return true;
        }
        return false;

    }

    private List<Node> findAllCandidates(Node node, int nodeHeight) {
        int v = node.initialMin;
        List<Node> path = new ArrayList<>(nodeHeight + 2);
        for (int i = 0; i < nodeHeight; i++) {
            path.add(null);
        }

        Node cur = head;
        int curDepth = maxLevel.get();
        while (curDepth >= nodeHeight) {
            Node next = cur.next.get(curDepth);
            while (next != tail && v >= next.initialMin) {
                cur = next;
                next = cur.next.get(curDepth);
            }
            curDepth--;
        }
        while (curDepth >= 1) {
            Node next = cur.next.get(curDepth);
            while (next != tail && v >= next.initialMin) {
                cur = next;
                next = cur.next.get(curDepth);
            }
            path.set(curDepth, cur);
            curDepth--;
        }
        return path;
    }

    private List<Node> findAllPrevs(Node node) {
        int curDepth = maxLevel.get();
        int v = node.initialMin;
        int nodeHeight = node.next.size();

        List<Node> path = new ArrayList<>(nodeHeight + 2);
        for (int i = 0; i < nodeHeight; i++) {
            path.add(null);
        }

        Node cur = head;
        while (curDepth >= nodeHeight) {
            Node next = cur.next.get(curDepth);
            while (next != tail && v > next.initialMin) {
                cur = next;
                next = cur.next.get(curDepth);
            }
            curDepth--;
        }
        Node curStart = cur;
        while (curDepth >= 0) {
            Node next = cur.next.get(curDepth);
            while (next != node) {
                cur = next;
                next = cur.next.get(curDepth);
            }
            path.set(curDepth, cur);
            curDepth--;
        }
        return path;
    }

    private Node find(int v) {
        Node cur = head;
        int curDepth = maxLevel.get();
        while (curDepth >= 0) {
            Node next = cur.next.get(curDepth);
            while (next != tail && v >= next.initialMin) {
                cur = next;
                next = next.next.get(curDepth);
            }
            curDepth--;
        }
        return cur;
    }

    @Override
    public boolean contains(Object element1) {
        int element = (Integer) element1;
        Node cur = find(element);
        do {
            if (cur.has(element)) {
                return true;
            }
            cur = cur.next.get(0);
        } while (cur != tail && element >= cur.initialMin);
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

    private void check(boolean cond) {
        check(cond, "");
    }

    private void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    public AtomicInteger getMaxLevel() {
        return maxLevel;
    }
}
