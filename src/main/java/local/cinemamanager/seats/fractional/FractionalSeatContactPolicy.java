package local.cinemamanager.seats.fractional;

/** Pure numeric boundary for the selected 1.50 m chair's explicitly calibrated seated contact. */
public final class FractionalSeatContactPolicy {
    public static final double EPS = 1e-6;
    public static final double MAX_CONTACT_DEPTH = .145;
    public static final double MARKER_ABOVE_FLOOR = .455;
    public static final double ATTACHMENT_Y = .6;
    public static final double PLAYER_WIDTH = .6, PLAYER_HEIGHT = 1.8;
    public record Point(double x, double y, double z) {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public record Sample(boolean selectedModel, boolean markerProfile, boolean standingPose, double playerScale,
                         Point floor, Point marker, Point passenger, Point attachment, Point feet,
                         double dimensionWidth, double dimensionHeight, Bounds body) {}
    public enum Failure { NONE, NONFINITE, MODEL, MARKER, POSE, SCALE, DIMENSIONS, ATTACHMENT, PASSENGER_POINT, CENTERING, BODY_POSITION, CONTACT_DEPTH }
    public static Failure validate(Sample s) {
        if (s == null || !finite(s.floor()) || !finite(s.marker()) || !finite(s.passenger()) || !finite(s.attachment())
            || !finite(s.feet()) || s.body() == null || !Double.isFinite(s.playerScale())
            || !Double.isFinite(s.dimensionWidth()) || !Double.isFinite(s.dimensionHeight())
            || !finite(s.body())) return Failure.NONFINITE;
        if (!s.selectedModel()) return Failure.MODEL;
        if (!s.markerProfile() || !near(s.marker().x(), s.floor().x()) || !near(s.marker().z(), s.floor().z())
            || !near(s.marker().y(), s.floor().y() + MARKER_ABOVE_FLOOR)) return Failure.MARKER;
        if (!s.standingPose()) return Failure.POSE;
        // Scale has an exact vanilla default; do not accept a small mod-provided scale override.
        if (s.playerScale() != 1.0) return Failure.SCALE;
        Bounds b = s.body();
        if (!near(s.dimensionWidth(), PLAYER_WIDTH) || !near(s.dimensionHeight(), PLAYER_HEIGHT)
            || !near(b.maxX() - b.minX(), PLAYER_WIDTH) || !near(b.maxZ() - b.minZ(), PLAYER_WIDTH)
            || !near(b.maxY() - b.minY(), PLAYER_HEIGHT)) return Failure.DIMENSIONS;
        if (!near(s.attachment().x(), 0) || !near(s.attachment().z(), 0) || !near(s.attachment().y(), ATTACHMENT_Y)) return Failure.ATTACHMENT;
        if (!near(s.passenger().x(), s.marker().x()) || !near(s.passenger().y(), s.marker().y())
            || !near(s.passenger().z(), s.marker().z())) return Failure.PASSENGER_POINT;
        if (!near(s.feet().x(), s.floor().x()) || !near(s.feet().z(), s.floor().z())
            || !near((b.minX() + b.maxX()) / 2, s.floor().x()) || !near((b.minZ() + b.maxZ()) / 2, s.floor().z())) return Failure.CENTERING;
        double depth = s.floor().y() - b.minY();
        if (depth < 0 || depth > MAX_CONTACT_DEPTH + EPS) return Failure.CONTACT_DEPTH;
        if (!near(b.minY(), s.feet().y()) || !near(s.feet().x(), s.passenger().x() - s.attachment().x())
            || !near(s.feet().y(), s.passenger().y() - s.attachment().y())
            || !near(s.feet().z(), s.passenger().z() - s.attachment().z())) return Failure.BODY_POSITION;
        return Failure.NONE;
    }
    private static boolean near(double a, double b) { return Math.abs(a - b) <= EPS; }
    private static boolean finite(Point p) { return p != null && Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()); }
    private static boolean finite(Bounds b) { return Double.isFinite(b.minX()) && Double.isFinite(b.minY()) && Double.isFinite(b.minZ())
        && Double.isFinite(b.maxX()) && Double.isFinite(b.maxY()) && Double.isFinite(b.maxZ()); }
    private FractionalSeatContactPolicy() {}
}
