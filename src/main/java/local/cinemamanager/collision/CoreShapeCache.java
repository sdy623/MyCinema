package local.cinemamanager.collision;

import java.util.Objects;
import java.util.function.Function;

/** Per-tile lazy cache. Every accepted load, replacement or rejection invalidates it. */
public final class CoreShapeCache<T> {
    private final Function<CoreShapeData, T> builder;
    private CoreShapeData data = CoreShapeData.EMPTY;
    private T cached;
    private boolean lastInputValid = true;

    public CoreShapeCache(Function<CoreShapeData, T> builder) { this.builder = Objects.requireNonNull(builder); }
    public CoreShapeData data() { return data; }
    public boolean lastInputValid() { return lastInputValid; }
    public T shape() {
        if (cached == null) cached = Objects.requireNonNull(builder.apply(data));
        return cached;
    }
    public boolean replacePacked(int version, int resolution, int[] packed) {
        try {
            replace(CoreShapeData.fromPacked(version, resolution, packed));
            return true;
        } catch (IllegalArgumentException malformed) {
            reject();
            return false;
        }
    }
    public void replace(CoreShapeData value) {
        data = Objects.requireNonNull(value);
        cached = null;
        lastInputValid = true;
    }
    public void invalidate() { cached = null; }
    public void reject() {
        data = CoreShapeData.EMPTY;
        cached = null;
        lastInputValid = false;
    }
}
