package local.cinemamanager.doors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

/** Optional manual-entry restriction. Authorization and opening pulses stay with the map/controller. */
public enum DoorAccess implements StringRepresentable {
    PUBLIC("public"), STAFF_FRONT("staff_front"), STAFF_BACK("staff_back");
    private final String name;
    DoorAccess(String name) { this.name = name; }
    @Override public String getSerializedName() { return name; }

    /** Front means the FACING direction, measured from the actual CLOSED panel mid-plane. */
    public boolean allows(Direction facing, BlockPos base, Vec3 actor) {
        if (this == PUBLIC) return true;
        if (facing == null || !facing.getAxis().isHorizontal() || base == null || actor == null
                || !Double.isFinite(actor.x) || !Double.isFinite(actor.z)) return false;
        double signed = (actor.x - base.getX() - .5) * facing.getStepX()
                + (actor.z - base.getZ() - .5) * facing.getStepZ()
                + .5 - DoorGeometry.THICKNESS / 2;
        // At the seal plane neither side is trusted. Open state and hit normal never change entry side.
        return this == STAFF_FRONT ? signed < -1e-6 : signed > 1e-6;
    }
}
