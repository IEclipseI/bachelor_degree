package mine.util;

public class Statistic {
    public static ThreadLocal<Statistic> threadLocal = ThreadLocal.withInitial(Statistic::new);
    public int successfulOptimisticAdds = 0;
    public int failedOptimisticAdds = 0;
    public int successfulOptimisticRemoves = 0;
    public int failedOptimisticRemoves = 0;
    public int moveForwardRequests = 0;
    public int nodesMovedForward = 0;
    public int findNotDeletedCalls = 0;
    public int deletedNodesTraversed = 0;
}
