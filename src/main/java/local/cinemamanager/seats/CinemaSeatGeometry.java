package local.cinemamanager.seats;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;

/** One-cell geometry. The matching machine-readable contract includes every model element. */
public final class CinemaSeatGeometry {
    public record Part(String name, String material, double x0, double y0, double z0,
                       double x1, double y1, double z1) {}

    public static final List<Part> PARTS = List.of(
        new Part("pedestal_foot", "frame", 5,0,5,11,1,13),
        new Part("pedestal", "frame", 7,1,7,9,5.5,11),
        new Part("seat_frame", "frame", 2,5.5,2,14,6.5,14),
        new Part("seat_cushion", "upholstery", 3,6.5,2,13,8.5,12),
        new Part("back_frame", "frame", 2,6.5,13,14,15.5,15),
        new Part("back_cushion", "upholstery", 3,8.5,12,13,16,14),
        new Part("left_arm_support", "frame", 1.5,6.5,8,3,10,12),
        new Part("right_arm_support", "frame", 13,6.5,8,14.5,10,12),
        new Part("left_arm_pad", "upholstery", 1,10,1,3,11.5,13),
        new Part("right_arm_pad", "upholstery", 13,10,1,15,11.5,13)
    );
    private static final VoxelShape[] SHAPES = new VoxelShape[4];
    static {
        for (int turns=0; turns<4; turns++) {
            VoxelShape shape=Shapes.empty();
            for (Part part:PARTS) {
                double x0=part.x0(), z0=part.z0(), x1=part.x1(), z1=part.z1();
                for (int i=0; i<turns; i++) {
                    double oldX0=x0, oldX1=x1;
                    x0=16-z1; x1=16-z0; z0=oldX0; z1=oldX1;
                }
                shape=Shapes.or(shape, Block.box(x0,part.y0(),z0,x1,part.y1(),z1));
            }
            SHAPES[turns]=shape.optimize();
        }
    }
    public static int turns(Direction facing) {
        return switch(facing) { case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3;
            default -> throw new IllegalArgumentException("Cinema chairs face horizontally"); };
    }
    public static VoxelShape shape(Direction facing) { return SHAPES[turns(facing)]; }

    /** Source anchor, NOT a measured seated eye/feet/pose height. Vanilla subtracts the rider's vehicle attachment. */
    public static Vec3 mountPosition(BlockPos pos, Direction facing) {
        double x=.5, z=7.0/16;
        for (int i=0; i<turns(facing); i++) { double oldX=x; x=1-z; z=oldX; }
        return new Vec3(pos.getX()+x, pos.getY()+8.5/16, pos.getZ()+z);
    }
    private CinemaSeatGeometry() {}
}
