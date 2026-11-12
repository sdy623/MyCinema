package local.cinemamanager.projection;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** World-space projected image geometry for MyCinema projectors. */
public final class ProjectionGeometry {
    private ProjectionGeometry() {}

    public static Vec3[] corners(CinemaProjectorTile tile) {
        Direction facing = tile.machineFacing();
        Vec3 normal = new Vec3(facing.getStepX(), 0, facing.getStepZ());
        Vec3 right = new Vec3(-normal.z, 0, normal.x);
        Vec3 center = tile.getBlockPos().getCenter().add(tile.projectionOffsetX, tile.projectionOffsetY, tile.projectionOffsetZ)
                .add(normal.scale(tile.data.projectionDistance));
        double halfW = tile.data.getWidth() / 2.0, halfH = tile.data.getHeight() / 2.0;
        Vec3 horizontal = right.scale(halfW), vertical = new Vec3(0, halfH, 0);
        return new Vec3[]{center.subtract(horizontal).subtract(vertical), center.add(horizontal).subtract(vertical),
                center.add(horizontal).add(vertical), center.subtract(horizontal).add(vertical)};
    }

    public static Vec3 center(Vec3[] corners) {
        if (corners == null || corners.length == 0) return null;
        Vec3 total = Vec3.ZERO; for (Vec3 corner : corners) total = total.add(corner);
        return total.scale(1.0 / corners.length);
    }

    public static boolean aligned(Vec3[] first, Vec3[] second, double tolerance) {
        if (first == null || second == null || first.length != second.length) return false;
        boolean direct = true, reverse = true;
        for (int i = 0; i < first.length; i++) {
            direct &= first[i].distanceTo(second[i]) <= tolerance;
            reverse &= first[i].distanceTo(second[first.length - 1 - i]) <= tolerance;
        }
        return direct || reverse;
    }
}
