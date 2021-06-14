import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

public class SequentialSetImpl extends VerifierState implements Set<Integer> {
    private TreeSet<Integer> set = new TreeSet<>();

    @NotNull
    @Override
    protected Object extractState() {
        return set;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    public boolean contains(Integer o) {
        return set.contains(o);
    }

    @Override
    public boolean contains(Object o) {
        return set.contains(o);
    }

    @NotNull
    @Override
    public Iterator<Integer> iterator() {
        return null;
    }

    @NotNull
    @Override
    public Object[] toArray() {
        return new Object[0];
    }

    @NotNull
    @Override
    public <T> T[] toArray(@NotNull T[] a) {
        return null;
    }


    @Override
    public boolean add(Integer integer) {
        return set.add(integer);
    }

    public boolean remove(Integer o) {
        return set.remove(o);
    }

    @Override
    public boolean remove(Object o) {
        return set.remove(o);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return false;
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends Integer> c) {
        return false;
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        return false;
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        return false;
    }

    @Override
    public void clear() {

    }
}
