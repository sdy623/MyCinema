package local.cinemamanager.client;

import java.util.concurrent.TimeUnit;

/** Scalar-only client admission. Server authorization and its rate limit remain authoritative. */
final class PosterRequestAdmission {
    static final int MAX_IN_FLIGHT = 16;
    static final int MAX_PENDING = 32;
    static final long SPACING_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private boolean started;
    private long lastSent;

    boolean take(long now, int inFlight) {
        if (inFlight >= MAX_IN_FLIGHT || inFlight < 0
                || started && now - lastSent < SPACING_NANOS) return false;
        started = true;
        lastSent = now;
        return true;
    }
}
