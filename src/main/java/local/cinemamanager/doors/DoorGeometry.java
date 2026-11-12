package local.cinemamanager.doors;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared by collision and the resource generator's checked geometry contract. */
public final class DoorGeometry {
    private DoorGeometry() {}
    public static final double THICKNESS = 3.0 / 16.0;
    private static final VoxelShape NORTH_EDGE = Shapes.box(0, 0, 0, 1, 1, THICKNESS);
    private static final VoxelShape SOUTH_EDGE = Shapes.box(0, 0, 1-THICKNESS, 1, 1, 1);
    private static final VoxelShape WEST_EDGE = Shapes.box(0, 0, 0, THICKNESS, 1, 1);
    private static final VoxelShape EAST_EDGE = Shapes.box(1-THICKNESS, 0, 0, 1, 1, 1);

    /** Same plane and hinge convention as vanilla doors, but used at all three heights. */
    public static Direction panelEdge(Direction facing, DoorHingeSide hinge, boolean open) {
        if (!facing.getAxis().isHorizontal()) throw new IllegalArgumentException("Horizontal facing required");
        return open ? (hinge == DoorHingeSide.LEFT ? facing.getCounterClockWise() : facing.getClockWise()) : facing.getOpposite();
    }
    public static VoxelShape shape(Direction facing, DoorHingeSide hinge, boolean open) {
        return switch (panelEdge(facing, hinge, open)) {
            case NORTH -> NORTH_EDGE;
            case SOUTH -> SOUTH_EDGE;
            case WEST -> WEST_EDGE;
            case EAST -> EAST_EDGE;
            default -> throw new IllegalArgumentException("Horizontal facing required");
        };
    }
    /** Legacy geometry is untouched. A half-drop lower section extends down to its slab.
     * Upper edge/lintel and all block coordinates remain fixed. */
    public static VoxelShape shape(Direction facing, DoorHingeSide hinge, boolean open,
                                   DoorPart part, boolean halfDrop) {
        VoxelShape standard = shape(facing, hinge, open);
        if (!halfDrop || part != DoorPart.LOWER) return standard;
        AABB b = standard.bounds();
        return Shapes.box(b.minX, -.5, b.minZ, b.maxX, b.maxY, b.maxZ);
    }
    public static Direction partnerDirection(Direction facing, DoorHingeSide hinge) {
        return hinge == DoorHingeSide.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
    }
    public static boolean compatible(Direction facing, DoorHingeSide hinge, Direction otherFacing, DoorHingeSide otherHinge) {
        return facing == otherFacing && hinge != otherHinge;
    }
    /** A steady signal does not undo manual operation; a signal edge does. */
    public static boolean reconciledOpen(boolean signal, boolean wasPowered, boolean wasOpen, Boolean partnerPowered) {
        return signal != wasPowered || (partnerPowered != null && signal != partnerPowered) ? signal : wasOpen;
    }
}
