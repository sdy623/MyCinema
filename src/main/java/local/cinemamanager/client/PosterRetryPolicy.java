package local.cinemamanager.client;

/** Client-thread retry decisions. Heap snapshots and ticks are inputs so pressure is testable without allocations. */
final class PosterRetryPolicy {
    static final long MIB = 1024L * 1024;
    static final long FAILURE_TICKS = 600;
    static final long MEMORY_CHECK_TICKS = 20;
    static final int MAX_EARLY_RETRIES = 3;
    static final long RECOVERY_CUSHION = 8 * MIB;

    record Heap(long maximum, long available) {
        static Heap current() {
            Runtime runtime = Runtime.getRuntime();
            return new Heap(runtime.maxMemory(), runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        }
    }

    /** Scalar requirements only. No image bytes, source reference or authority is retained for a retry. */
    record Need(long inputBytes, long workingBytes) {
        Need {
            if (inputBytes < 1 || workingBytes < 0) throw new IllegalArgumentException("Invalid poster retry requirement");
        }
    }

    static Need readNeed(long inputBytes, Need previous) {
        return new Need(Math.max(inputBytes, previous == null ? 0 : previous.inputBytes),
                previous == null ? 0 : previous.workingBytes);
    }

    /** Only PanelAssetClient's response-time read gate reports (3 * encoded bytes, available after reserve). */
    static Need privateAssetReadNeed(PosterFailure failure, Need previous) {
        Object[] facts = failure.args();
        if (failure.code() != PosterFailure.Code.MEMORY_BUDGET || facts.length != 2
                || !(facts[0] instanceof Long required) || !(facts[1] instanceof Long available)
                || required < 36 || required % 3 != 0 || available < 0) return null;
        return readNeed(required / 3, previous);
    }

    private long retryAfter = Long.MAX_VALUE;
    private long nextCheck = Long.MAX_VALUE;
    private int earlyRetries;
    private boolean memoryFailure;

    void failed(long now, PosterFailure.Code failure) {
        memoryFailure = failure == PosterFailure.Code.MEMORY_BUDGET;
        retryAfter = now + FAILURE_TICKS;
        nextCheck = memoryFailure ? now + MEMORY_CHECK_TICKS : retryAfter;
    }

    boolean checkDue(long now) { return now >= nextCheck; }

    /** Called only for an ERROR entry; true consumes one retry decision, including on the same tick. */
    boolean retry(long now, Need need, Heap heap, int configuredInput, long configuredWork) {
        if (!checkDue(now)) return false;
        boolean ordinaryDue = now >= retryAfter;
        if (memoryFailure && need != null) {
            // Static policy/heap ceilings do not get early retries. Config changes are seen here too.
            boolean fitsCaps = need.inputBytes <= Math.min(configuredInput, heap.maximum / 6)
                    && need.workingBytes <= Math.min(configuredWork, heap.maximum / 3);
            if (fitsCaps) {
                if (!ordinaryDue && earlyRetries >= MAX_EARLY_RETRIES) {
                    nextCheck = retryAfter;
                    return false;
                }
                nextCheck = now + MEMORY_CHECK_TICKS;
                long recoveredAvailable = Math.max(0, heap.available - RECOVERY_CUSHION);
                // Keep the original reserves and gates. The extra cushion avoids retrying at the edge.
                if (recoveredAvailable < 32 * MIB
                        || inputBudget(configuredInput, heap.maximum, recoveredAvailable) < need.inputBytes
                        || (need.workingBytes > 0 && decodeBudget(configuredWork, heap.maximum,
                        recoveredAvailable, decodeReserve(heap.maximum)) < need.workingBytes)) return false;
            } else if (!ordinaryDue) {
                nextCheck = Math.min(now + MEMORY_CHECK_TICKS, retryAfter);
                return false;
            }
        } else if (!ordinaryDue) {
            nextCheck = retryAfter;
            return false;
        }
        if (ordinaryDue) earlyRetries = 0;
        else earlyRetries++;
        // The replacement Entry shares this policy; failed() schedules the next decision.
        nextCheck = Long.MAX_VALUE;
        return true;
    }

    static int inputBudget(int configured, long maxHeap, long available) {
        return (int) Math.max(1, Math.min(configured, Math.min(maxHeap / 6, Math.max(0, available - 32 * MIB) / 3)));
    }

    static long decodeReserve(long maxHeap) { return Math.max(16 * MIB, maxHeap / 10); }

    static long decodeBudget(long configured, long maxHeap, long available, long reserve) {
        return Math.min(configured, Math.min(maxHeap / 3, Math.max(0, available - reserve)));
    }
}
