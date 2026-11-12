package local.cinemamanager.client;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Client-thread texture ownership. A shared source never supplies another publication's authority. */
final class PosterTexturePool<T> {
    static final class Lease<T> {
        private final Slot<T> slot;
        private boolean closed, authorized;
        private String fingerprint;
        private Lease(Slot<T> slot) { this.slot = slot; }
    }

    private static final class Slot<T> {
        final Object owner;
        final String source;
        int references;
        boolean retired;
        T texture;
        String fingerprint;
        Lease<T> loading;
        Slot(Object owner, String source) { this.owner = owner; this.source = source; }
    }

    private final int capacity;
    private final Consumer<T> close;
    private final Map<String, Slot<T>> slots = new HashMap<>();

    PosterTexturePool(int capacity, Consumer<T> close) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid texture capacity");
        this.capacity = capacity;
        this.close = Objects.requireNonNull(close);
    }

    /** Reserves a source slot, but does not authorize reading or displaying its texture. */
    Lease<T> acquire(String source) {
        Objects.requireNonNull(source);
        Slot<T> slot = slots.get(source);
        if (slot == null) {
            if (slots.size() == capacity) return null;
            slot = new Slot<>(this, source);
            slots.put(source, slot);
        }
        slot.references++;
        return new Lease<>(slot);
    }

    private boolean active(Lease<T> lease) {
        return lease != null && !lease.closed && !lease.slot.retired
                && slots.get(lease.slot.source) == lease.slot;
    }

    /** One read/completion per source bounds decoded memory and gives waiting leases a turn. */
    boolean beginLoad(Lease<T> lease, boolean current) {
        if (!current || !active(lease) || lease.slot.loading != null) return false;
        lease.slot.loading = lease;
        return true;
    }

    void endLoad(Lease<T> lease) {
        if (lease != null && lease.slot.owner == this && lease.slot.loading == lease) lease.slot.loading = null;
    }

    boolean needsDecode(Lease<T> lease) {
        return active(lease) && lease.slot.texture == null;
    }

    /** Called only after this lease's own read and server authorization completed successfully. */
    boolean authorize(Lease<T> lease, String fingerprint, boolean current) {
        if (!current || !active(lease)) return false;
        lease.authorized = false;
        if (lease.slot.texture != null && !Objects.equals(lease.slot.fingerprint, fingerprint)) return false;
        lease.fingerprint = Objects.requireNonNull(fingerprint);
        lease.authorized = true;
        return true;
    }

    T get(Lease<T> lease, boolean current) {
        if (!current && lease != null && lease.slot.owner == this) lease.authorized = false;
        return current && active(lease) && lease.authorized && Objects.equals(lease.fingerprint,lease.slot.fingerprint)
                ? lease.slot.texture : null;
    }

    /** A rejected value remains caller-owned. Existing shared pixels are never replaced in place. */
    boolean install(Lease<T> lease, T texture, boolean current) {
        Objects.requireNonNull(texture);
        if (!current || !active(lease) || !lease.authorized || lease.slot.texture != null) return false;
        lease.slot.fingerprint = lease.fingerprint;
        lease.slot.texture = texture;
        return true;
    }

    void release(Lease<T> lease) {
        if (lease == null || lease.closed || lease.slot.owner != this) return;
        lease.closed = true;
        lease.authorized = false;
        endLoad(lease);
        Slot<T> slot = lease.slot;
        if (--slot.references == 0 && !slot.retired) {
            slots.remove(slot.source, slot);
            retire(slot);
        }
    }

    void clear() {
        for (Slot<T> slot : slots.values()) retire(slot);
        slots.clear();
    }

    private void retire(Slot<T> slot) {
        slot.retired = true;
        slot.loading = null;
        T texture = slot.texture;
        slot.texture = null;
        if (texture != null) close.accept(texture);
    }

    int size() { return slots.size(); }

    /** Pixels may be shared only after the viewer independently obtained these exact authorized bytes. */
    static String fingerprint(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
