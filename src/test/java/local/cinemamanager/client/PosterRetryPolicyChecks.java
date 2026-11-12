package local.cinemamanager.client;

/** Deterministic scalar-only checks: no real heap pressure, clocks, game client, network or image allocations. */
public final class PosterRetryPolicyChecks {
    private static final long MIB = 1024L * 1024;
    private static final int INPUT = 64 * 1024 * 1024;
    private static final long WORK = 256 * MIB;
    private static final PosterRetryPolicy.Heap HEALTHY = new PosterRetryPolicy.Heap(384 * MIB, 128 * MIB);
    private static final PosterRetryPolicy.Need READ = new PosterRetryPolicy.Need(16827, 0);
    private static final PosterRetryPolicy.Need DECODE = new PosterRetryPolicy.Need(16827, 9 * MIB);
    private static int checks;

    public static void main(String[] args) {
        unchangedBudgets();
        ordinaryFailures();
        readRecovery();
        privateAssetResponseRecovery();
        decodeRecovery();
        boundedRetries();
        staticCaps();
        System.out.println("PosterRetryPolicy checks passed: " + checks);
    }

    private static void unchangedBudgets() {
        for (long maximum : new long[]{96 * MIB, 384 * MIB, 448 * MIB, 2_048 * MIB}) {
            long reserve = Math.max(16 * MIB, maximum / 10);
            check(PosterRetryPolicy.decodeReserve(maximum) == reserve, "decode reserve is unchanged");
            for (long available : new long[]{0, 16 * MIB, 32 * MIB - 1, 32 * MIB, 32 * MIB + 3, 100 * MIB, maximum}) {
                int original = (int) Math.max(1, Math.min(INPUT, Math.min(maximum / 6,
                        Math.max(0, available - 32 * MIB) / 3)));
                check(PosterRetryPolicy.inputBudget(INPUT, maximum, available) == original, "input admission unchanged");
                check(PosterRetryPolicy.decodeBudget(WORK, maximum, available, reserve)
                        == Math.min(WORK, Math.min(maximum / 3, Math.max(0, available - reserve))), "decode admission unchanged");
            }
        }
    }

    private static void ordinaryFailures() {
        for (PosterFailure.Code code : PosterFailure.Code.values()) {
            if (code == PosterFailure.Code.MEMORY_BUDGET) continue;
            PosterRetryPolicy policy = new PosterRetryPolicy();
            policy.failed(100, code);
            check(!policy.checkDue(699), code + " keeps 600-tick delay");
            check(!retry(policy, 120, DECODE, HEALTHY), code + " cannot use memory shortcut");
            check(!retry(policy, 699, DECODE, HEALTHY), code + " does not retry early");
            check(retry(policy, 700, DECODE, new PosterRetryPolicy.Heap(384 * MIB, 0)), code + " retains ordinary retry");
            check(!retry(policy, 700, DECODE, HEALTHY), code + " decision consumed exactly once");
        }
        PosterRetryPolicy unknown = memoryFailure(0);
        check(!retry(unknown, 20, null, HEALTHY), "memory failure without scalar requirements does not guess");
        check(retry(unknown, 600, null, HEALTHY), "unknown memory failure keeps ordinary retry");
    }

    private static void readRecovery() {
        PosterRetryPolicy policy = memoryFailure(0);
        check(!retry(policy, 19, READ, HEALTHY), "first local pressure recheck waits 20 ticks");
        check(!retry(policy, 20, READ, new PosterRetryPolicy.Heap(384 * MIB, 32 * MIB)), "read reserve blocks early network retry");
        check(!policy.checkDue(39), "frames do not poll heap more than once per second");
        check(!retry(policy, 39, READ, HEALTHY), "recovered heap waits for next bounded local check");
        check(retry(policy, 40, READ, HEALTHY), "small input can recover well before 600 ticks");
        check(!retry(policy, 40, READ, HEALTHY), "repeated frames cannot submit duplicate retry");

        policy = memoryFailure(0);
        long threshold = 32 * MIB + PosterRetryPolicy.RECOVERY_CUSHION + 3 * READ.inputBytes();
        check(!retry(policy, 20, READ, new PosterRetryPolicy.Heap(384 * MIB, threshold - 1)), "input recovery requires original reserve, multiplier and cushion");
        check(retry(policy, 40, READ, new PosterRetryPolicy.Heap(384 * MIB, threshold)), "exact input recovery boundary is admitted");

        policy = memoryFailure(0);
        for (int t = 20; t <= 1_200; t += 20)
            check(!retry(policy, t, READ, new PosterRetryPolicy.Heap(384 * MIB, 31 * MIB)), "persistent pressure does not contact server even after 600 ticks");
        check(retry(policy, 1_220, READ, HEALTHY), "late heap recovery permits a request");
    }

    private static void privateAssetResponseRecovery() {
        // Heap pressure can rise between request admission and the authorized asset metadata reply.
        PosterFailure direct = new PosterFailure(PosterFailure.Code.MEMORY_BUDGET, 16827L * 3, 0L);
        PosterRetryPolicy.Need need = PosterRetryPolicy.privateAssetReadNeed(direct, null);
        check(need != null && need.inputBytes() == 16827 && need.workingBytes() == 0,
                "direct private-asset MEMORY_BUDGET uses encoded size, not a decode estimate");
        PosterRetryPolicy policy = memoryFailure(0);
        long threshold = 32 * MIB + PosterRetryPolicy.RECOVERY_CUSHION + 16827L * 3;
        check(!retry(policy, 20, need, new PosterRetryPolicy.Heap(384 * MIB, threshold - 1)),
                "private-asset response retry keeps the three-times-input gate and reserve");
        check(retry(policy, 40, need, new PosterRetryPolicy.Heap(384 * MIB, threshold)),
                "direct private-asset memory failure can recover before 600 ticks");
        need = PosterRetryPolicy.privateAssetReadNeed(direct, DECODE);
        check(need.inputBytes() == DECODE.inputBytes() && need.workingBytes() == DECODE.workingBytes(),
                "response-time read pressure preserves prior decode requirement");
        policy = memoryFailure(0);
        check(!retry(policy, 20, need, new PosterRetryPolicy.Heap(384 * MIB, threshold)),
                "read recovery alone cannot erase known decode pressure");
        check(retry(policy, 40, need, HEALTHY), "both requirements recover after direct asset failure");
        need = PosterRetryPolicy.privateAssetReadNeed(direct, new PosterRetryPolicy.Need(20000, 9 * MIB));
        check(need.inputBytes() == 20000 && need.workingBytes() == 9 * MIB, "keep prior larger encoded requirement");
        for (PosterFailure invalid : new PosterFailure[]{
                new PosterFailure(PosterFailure.Code.PERMISSION, 16827L * 3, 0L),
                new PosterFailure(PosterFailure.Code.DOWNLOAD_BUDGET, 16827L * 3, 0L),
                new PosterFailure(PosterFailure.Code.MEMORY_BUDGET),
                new PosterFailure(PosterFailure.Code.MEMORY_BUDGET, 16827L * 3 + 1, 0L),
                new PosterFailure(PosterFailure.Code.MEMORY_BUDGET, 33L, 0L),
                new PosterFailure(PosterFailure.Code.MEMORY_BUDGET, 16827L * 3, -1L),
                new PosterFailure(PosterFailure.Code.MEMORY_BUDGET, 16827.5 * 3, 0L),
                new PosterFailure(PosterFailure.Code.MEMORY_BUDGET, true, 0L)
        }) check(PosterRetryPolicy.privateAssetReadNeed(invalid, DECODE) == null,
                "unknown response facts do not inherit an unsafe inferred shortcut");
        policy = memoryFailure(0);
        check(!retry(policy, 20, null, HEALTHY), "unsupported scalar convention falls back to ordinary backoff");
        check(retry(policy, 600, null, HEALTHY), "unsupported scalar convention retries at 600 ticks");
    }

    private static void decodeRecovery() {
        PosterRetryPolicy policy = memoryFailure(0);
        long maximum = 448 * MIB;
        long threshold = PosterRetryPolicy.decodeReserve(maximum) + DECODE.workingBytes() + PosterRetryPolicy.RECOVERY_CUSHION;
        check(!retry(policy, 20, DECODE, new PosterRetryPolicy.Heap(maximum, threshold - 1)), "decode recovery keeps actual 448 MiB heap reserve plus cushion");
        check(retry(policy, 40, DECODE, new PosterRetryPolicy.Heap(maximum, threshold)), "decode recovery is eligible before 30 seconds");

        policy = memoryFailure(0);
        PosterRetryPolicy.Need largeInput = new PosterRetryPolicy.Need(30 * MIB, 9 * MIB);
        check(!retry(policy, 20, largeInput, new PosterRetryPolicy.Heap(maximum, threshold)), "decode retry also rechecks encoded-input admission");
        check(retry(policy, 40, largeInput, new PosterRetryPolicy.Heap(maximum, 160 * MIB)), "both read and decode gates must recover");
    }

    private static void boundedRetries() {
        PosterRetryPolicy policy = memoryFailure(0);
        for (int attempt = 1; attempt <= PosterRetryPolicy.MAX_EARLY_RETRIES; attempt++) {
            long now = attempt * 20L;
            check(retry(policy, now, DECODE, HEALTHY), "bounded early retry " + attempt);
            check(!retry(policy, now, DECODE, HEALTHY), "same-tick duplicate rejected");
            // The replacement entry keeps this same policy, even if pressure returns during the request.
            policy.failed(now, PosterFailure.Code.MEMORY_BUDGET);
        }
        check(!retry(policy, 80, DECODE, HEALTHY), "fourth early request suppressed despite healthy snapshot");
        check(!policy.checkDue(659), "exhausted early allowance waits full ordinary backoff");
        check(retry(policy, 660, DECODE, HEALTHY), "ordinary retry resets early allowance");
        policy.failed(660, PosterFailure.Code.MEMORY_BUDGET);
        check(retry(policy, 680, DECODE, HEALTHY), "new bounded recovery allowance after ordinary retry");
        policy.failed(681, PosterFailure.Code.PERMISSION);
        check(!retry(policy, 701, DECODE, HEALTHY), "permission denial after memory retry never inherits a shortcut");
        check(retry(policy, 1_281, DECODE, HEALTHY), "permission retry retains existing 600 ticks");
    }

    private static void staticCaps() {
        PosterRetryPolicy.Need[] capped = {
                new PosterRetryPolicy.Need(INPUT + 1L, 0),
                new PosterRetryPolicy.Need(1, WORK + 1),
                new PosterRetryPolicy.Need(1, HEALTHY.maximum() / 3 + 1)
        };
        for (PosterRetryPolicy.Need need : capped) {
            PosterRetryPolicy policy = memoryFailure(0);
            check(!retry(policy, 20, need, HEALTHY), "static ceiling never gets fast retry");
            check(!retry(policy, 599, need, HEALTHY), "static ceiling keeps ordinary delay");
            check(retry(policy, 600, need, HEALTHY), "static ceiling keeps existing ordinary retry");
        }
        PosterRetryPolicy policy = memoryFailure(0);
        PosterRetryPolicy.Heap smallHeap = new PosterRetryPolicy.Heap(192 * MIB, 192 * MIB);
        check(!retry(policy, 20, new PosterRetryPolicy.Need(33 * MIB, 0), smallHeap), "input one-sixth maximum-heap ceiling remains");
        policy = memoryFailure(0);
        check(!policy.retry(20, new PosterRetryPolicy.Need(1, 80 * MIB), HEALTHY, INPUT, 64 * MIB), "configured decode ceiling not treated as transient pressure");
        check(policy.retry(40, new PosterRetryPolicy.Need(1, 80 * MIB), HEALTHY, INPUT, WORK), "current config is rechecked without changing any setting");
    }

    private static PosterRetryPolicy memoryFailure(long now) {
        PosterRetryPolicy policy = new PosterRetryPolicy();
        policy.failed(now, PosterFailure.Code.MEMORY_BUDGET);
        return policy;
    }
    private static boolean retry(PosterRetryPolicy policy, long tick, PosterRetryPolicy.Need need, PosterRetryPolicy.Heap heap) {
        return policy.retry(tick, need, heap, INPUT, WORK);
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
