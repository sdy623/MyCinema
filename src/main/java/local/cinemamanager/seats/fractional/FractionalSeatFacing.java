package local.cinemamanager.seats.fractional;

import java.util.Objects;

/** One-time mount alignment. This policy is never reapplied during ordinary looking or riding ticks. */
public final class FractionalSeatFacing {
    public record Rotation(float yaw, float previousYaw, float headYaw, float previousHeadYaw,
                           float bodyYaw, float previousBodyYaw, float pitch) {}
    public static float frontYaw(int turns) {
        return switch (turns) { case 0 -> 180f; case 1 -> 270f; case 2 -> 0f; case 3 -> 90f;
            default -> throw new IllegalArgumentException("Invalid chair quarter-turn"); };
    }
    public static Rotation afterSuccessfulMount(boolean successful, int turns, Rotation current) {
        Objects.requireNonNull(current);
        if (!successful) return current;
        float yaw = frontYaw(turns);
        return new Rotation(yaw, yaw, yaw, yaw, yaw, yaw, current.pitch());
    }
    private FractionalSeatFacing() {}
}
