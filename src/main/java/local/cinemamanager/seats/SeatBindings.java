package local.cinemamanager.seats;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Loaded-only identity diagnostics. Serviceability and occupancy are separate concerns. */
public final class SeatBindings {
    public static String blockType(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();}
    public static String status(Level level,SeatRegistry.Seat seat){
        BlockPos pos=BlockPos.of(seat.position());
        if(!level.hasChunkAt(pos))return "unloaded";
        BlockState state=level.getBlockState(pos);if(state.isAir())return "missing";
        if(!seat.verifiedIdentity())return seat.block().isEmpty()||seat.block().equals(blockType(state))?"unverified":"changed";
        var physical=CinemaSeatBlock.findSeat(level,pos);
        if(physical.isEmpty())return "changed";
        return physical.get().seatUid().toString().equals(seat.seatUid())?"present":"replaced";
    }
    private SeatBindings(){}
}
