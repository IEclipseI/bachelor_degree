import mine.KSkipListConcurrentGeneric;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.junit.jupiter.api.Test;

import java.util.Set;

@StressCTest(sequentialSpecification = SequentialSetImpl.class, iterations = 10000)
@Param(name = "key", gen = IntGen.class, conf = "1:5")
public class LinCheck {
    private final Set<Integer> set = new KSkipListConcurrentGeneric<>();

    @Operation(params = "key")
    public boolean add(Integer x) {
        return set.add(x);
    }

    @Operation(params = "key")
    public boolean contains(Integer x) {
        return set.contains(x);
    }

    @Operation(params = "key")
    public boolean remove(Integer x) {
        return set.remove(x);
    }

    @Test
    public void test() {
        LinChecker.check(LinCheck.class);
    }
}
