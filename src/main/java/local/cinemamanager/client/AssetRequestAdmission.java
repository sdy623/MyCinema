package local.cinemamanager.client;

import java.util.concurrent.TimeUnit;

/** Upload starts and downloads share the server's four-starts-per-20-ticks allowance. */
final class AssetRequestAdmission {
    static final long SPACING_NANOS=TimeUnit.MILLISECONDS.toNanos(500);
    private boolean started;
    private long lastSent;

    boolean take(long now){
        if(started&&now-lastSent<SPACING_NANOS)return false;
        started=true;
        lastSent=now;
        return true;
    }
}
