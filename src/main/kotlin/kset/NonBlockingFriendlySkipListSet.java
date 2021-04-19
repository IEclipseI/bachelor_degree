package kset;

import java.util.AbstractSet;
import java.util.Iterator;

public class NonBlockingFriendlySkipListSet<E> extends AbstractSet<E> {
    NonBlockingFriendlySkipListMap<E, Boolean> map;

    public NonBlockingFriendlySkipListSet() {
        this.map = new NonBlockingFriendlySkipListMap<>();
    }

    @Override
    public Iterator<E> iterator() {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean add(E element1) {
       return map.put(element1, false) == null;
    }

    @Override
    public boolean remove(Object element1) {
        return map.remove(element1) == null;
    }

    @Override
    public boolean contains(Object element1) {
        return map.containsKey(element1);
    }
}
