package local.cinemamanager.seats;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;
import java.util.Optional;

/** Server-only chair entry/exit checks. No chunk generation, nearest-air teleport or unsafe fallback. */
final class CinemaSeatSafety {
    static boolean canSit(ServerLevel level,LivingEntity rider,CinemaSeatMount mount) {
        Vec3 feet=mount.getPassengerRidingPosition(rider).subtract(rider.getVehicleAttachmentPoint(mount));
        AABB seated=rider.getDimensions(Pose.STANDING).makeBoundingBox(feet);
        // The vanilla seated passenger origin can fall below the cushion (or floor). Own chair/support
        // are intentional visual overlap; other blocks, players and overhead geometry still matter.
        return clear(level,rider,seated,mount.seatPos(),mount.seatPos().below())
            && clear(level,rider,rider.getDimensions(Pose.STANDING).makeBoundingBox(
                new Vec3(mount.getX(),mount.seatPos().getY(),mount.getZ())),mount.seatPos(),null);
    }

    static Optional<Vec3> findExit(ServerLevel level,LivingEntity rider,CinemaSeatMount mount,boolean removedChair) {
        BlockPos seat=mount.seatPos();
        Direction front=mount.facing(), left=front.getCounterClockWise(), right=front.getClockWise();
        BlockPos[] columns={seat.relative(front),seat.relative(front).relative(left),seat.relative(front).relative(right),
            seat.relative(left),seat.relative(right),seat.relative(front.getOpposite())};
        for (BlockPos column:columns) for (int dy:new int[]{0,1,-1}) {
            BlockPos candidate=column.offset(0,dy,0);
            if (!loaded(level,new AABB(candidate).inflate(1,2,1))) continue;
            Vec3 exit=DismountHelper.findSafeDismountLocation(rider.getType(),level,candidate,true);
            if (exit==null || Math.abs(exit.y-seat.getY())>1.001 || !safeStanding(level,rider,exit)) continue;
            if (corridor(level,rider,seat,mount.position(),exit)) return Optional.of(exit);
        }
        if (removedChair) {
            Vec3 inCell=new Vec3(mount.getX(),seat.getY(),mount.getZ());
            if (safeStanding(level,rider,inCell)) return Optional.of(inCell);
        }
        return Optional.empty();
    }

    static boolean safeStanding(ServerLevel level,LivingEntity rider,Vec3 feet) {
        AABB box=rider.getDimensions(Pose.STANDING).makeBoundingBox(feet);
        if (!clear(level,rider,box,null,null)) return false;
        // Require actual collision support immediately below the player's feet, including slab tops.
        AABB soles=new AABB(box.minX+.01,feet.y-.02,box.minZ+.01,box.maxX-.01,feet.y,box.maxZ-.01);
        return level.getBlockCollisions(rider,soles).iterator().hasNext();
    }

    private static boolean corridor(ServerLevel level,LivingEntity rider,BlockPos ownChair,Vec3 start,Vec3 exit) {
        // Rise in the chair's own cell before stepping out, then lower at the destination. Sampling
        // overlapping player volumes at <= 1/8 block also rejects diagonal corner/wall shortcuts.
        double travelY=Math.max(ownChair.getY(),exit.y);
        Vec3 above=new Vec3(start.x,travelY,start.z), across=new Vec3(exit.x,travelY,exit.z);
        return segment(level,rider,ownChair,new Vec3(start.x,ownChair.getY(),start.z),above)
            && segment(level,rider,ownChair,above,across) && segment(level,rider,ownChair,across,exit);
    }
    private static boolean segment(ServerLevel level,LivingEntity rider,BlockPos ownChair,Vec3 start,Vec3 end) {
        int steps=Math.max(1,(int)Math.ceil(start.distanceTo(end)*8));
        AABB previous=rider.getDimensions(Pose.STANDING).makeBoundingBox(start);
        for (int i=0;i<=steps;i++) {
            Vec3 point=start.lerp(end,(double)i/steps);
            AABB current=rider.getDimensions(Pose.STANDING).makeBoundingBox(point);
            if (!clear(level,rider,previous.minmax(current),ownChair,null)) return false;
            previous=current;
        }
        return true;
    }
    private static boolean loaded(ServerLevel level,AABB box) {
        BlockPos min=BlockPos.containing(box.minX,box.minY,box.minZ),max=BlockPos.containing(box.maxX,box.maxY,box.maxZ);
        return min.getY()>=level.getMinBuildHeight() && max.getY()<level.getMaxBuildHeight()
            && level.hasChunksAt(min,max) && level.getWorldBorder().isWithinBounds(box);
    }
    private static boolean clear(ServerLevel level,LivingEntity rider,AABB box,BlockPos ignoredChair,BlockPos ignoredSupport) {
        if (!loaded(level,box.inflate(1)) || !level.getEntityCollisions(rider,box).isEmpty()) return false;
        VoxelShape body=Shapes.create(box.deflate(1.0e-7));
        // Include neighboring cells: fences/walls and other blocks may have collision above their own cell.
        for (BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(box.minX-1,box.minY-1,box.minZ-1),
                BlockPos.containing(box.maxX+1,box.maxY+1,box.maxZ+1))) {
            if (pos.equals(ignoredChair) || pos.equals(ignoredSupport)) continue;
            BlockState state=level.getBlockState(pos);
            if (new AABB(pos).intersects(box.deflate(1.0e-7)) && (!state.getFluidState().isEmpty()
                || state.is(net.minecraft.world.level.block.Blocks.FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE))) return false;
            VoxelShape collision=state.getCollisionShape(level,pos,CollisionContext.of(rider)).move(pos.getX(),pos.getY(),pos.getZ());
            if (Shapes.joinIsNotEmpty(body,collision,BooleanOp.AND)) return false;
        }
        return true;
    }
    private CinemaSeatSafety() {}
}
