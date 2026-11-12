package local.cinemamanager.access;

import java.util.*;

/** Server-owned capabilities. Item NBT contains only an opaque lookup key, never permissions. */
public final class StaffGrantBook {
    public static final int MAX_GRANTS = 4096, MAX_CINEMA_GRANTS = 128;
    public static final int MAX_READER_GRANTS = MAX_CINEMA_GRANTS; // Legacy source compatibility.
    public record Scope(UUID controller, UUID controllerOwner, long controllerPos,
                        UUID reader, UUID readerOwner, long readerPos, UUID door, long doorPos, UUID revision) {
        public Scope {
            Objects.requireNonNull(controller); Objects.requireNonNull(controllerOwner);
            Objects.requireNonNull(reader); Objects.requireNonNull(readerOwner);
            Objects.requireNonNull(door); Objects.requireNonNull(revision);
        }
        /** Reader/door fields are issuance provenance for old saves, never the card's authority boundary. */
        public boolean sameCinema(Scope other) {
            return other!=null&&controller.equals(other.controller)&&controllerOwner.equals(other.controllerOwner)&&controllerPos==other.controllerPos;
        }
    }
    public record Grant(UUID card, UUID player, UUID issuer, Scope scope, StaffRole role) {
        public Grant { Objects.requireNonNull(card); Objects.requireNonNull(player); Objects.requireNonNull(issuer); Objects.requireNonNull(scope); Objects.requireNonNull(role); }
    }
    private final Map<UUID, Grant> grants = new LinkedHashMap<>();
    public Grant issue(UUID target, UUID issuer, Scope scope, StaffRole role) {
        Objects.requireNonNull(target); Objects.requireNonNull(issuer); Objects.requireNonNull(scope); Objects.requireNonNull(role);
        long replaced = grants.values().stream().filter(g -> g.player().equals(target) && g.scope().sameCinema(scope)).count();
        if (grants.size() - replaced >= MAX_GRANTS || (replaced==0 && forCinema(scope).stream().map(Grant::player).distinct().count() >= MAX_CINEMA_GRANTS))
            throw new IllegalArgumentException("mycinema.access.limit");
        revokeCinema(scope, target);
        Grant grant = new Grant(UUID.randomUUID(), target, issuer, scope, role);
        grants.put(grant.card(), grant);
        return grant;
    }
    public boolean authorize(UUID card, UUID actor, Scope current, StaffRole required) {
        Grant g = grants.get(card);
        return g != null && g.player().equals(actor) && g.scope().sameCinema(current) && g.role().allows(required);
    }
    public boolean revokeCinema(Scope scope, UUID target) { return grants.values().removeIf(g -> g.scope().sameCinema(scope) && g.player().equals(target)); }
    public List<Grant> forCinema(Scope scope) { return grants.values().stream().filter(g -> g.scope().sameCinema(scope)).toList(); }
    /** Legacy maintenance API: production revocation uses the explicit cinema scope. */
    public boolean revoke(UUID reader, UUID target) {
        return grants.values().removeIf(g -> g.scope().reader().equals(reader) && g.player().equals(target));
    }
    public boolean revokeReader(UUID reader) { return grants.values().removeIf(g -> g.scope().reader().equals(reader)); }
    public boolean revokeDoor(UUID door) { return grants.values().removeIf(g -> g.scope().door().equals(door)); }
    public List<Grant> forReader(UUID reader) { return grants.values().stream().filter(g -> g.scope().reader().equals(reader)).toList(); }
    public Collection<Grant> all() { return List.copyOf(grants.values()); }
    /** Duplicate IDs in persisted data fail closed instead of selecting a potentially forged grant. */
    public void restore(Collection<Grant> saved) {
        grants.clear(); Set<UUID> duplicates = new HashSet<>();
        for (Grant g : saved) {
            if (g == null || duplicates.contains(g.card())) continue;
            if (grants.containsKey(g.card())) { grants.remove(g.card()); duplicates.add(g.card()); continue; }
            // Preserve existing multi-reader deployments up to the original global bound.
            // The new issuance limit must never discard already-authorized legacy employees.
            if (grants.size() < MAX_GRANTS) grants.put(g.card(), g);
        }
    }
}
