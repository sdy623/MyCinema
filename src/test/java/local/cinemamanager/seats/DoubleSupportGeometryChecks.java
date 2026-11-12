package local.cinemamanager.seats;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Executes the actual Minecraft shape constructor, including its bounded startup cost. */
public final class DoubleSupportGeometryChecks {
    public static void main(String[] args) {
        for (Direction facing:Direction.Plane.HORIZONTAL) {
            var shape=DoubleSupportSeatGeometry.shape(facing);
            var bounds=shape.bounds();
            if(shape.isEmpty()||bounds.minX<0||bounds.minY!=0||bounds.minZ<0
                    ||bounds.maxX>1||bounds.maxY>1||bounds.maxZ>1)
                throw new AssertionError("Variant shape must stay inside one occupied cell: "+facing);
        }
        long nanos=DoubleSupportSeatGeometry.constructionNanos();
        if(nanos>5_000_000_000L)throw new AssertionError("Actual shape construction exceeded five seconds: "+nanos);
        var north=DoubleSupportSeatGeometry.shape(Direction.NORTH);
        if(north.clip(new Vec3(.5,.1,-1),new Vec3(.5,.1,2),BlockPos.ZERO)!=null)
            throw new AssertionError("The two supports must leave their center gap open");
        if(north.clip(new Vec3(.3,.02,-1),new Vec3(.3,.02,2),BlockPos.ZERO)==null)
            throw new AssertionError("The real left support foot must be selectable");
        System.out.println("Double support actual shape checks passed; four-shape construction ms="+(nanos/1_000_000.0));
    }
}
