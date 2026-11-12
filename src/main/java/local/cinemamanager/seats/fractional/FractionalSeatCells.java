package local.cinemamanager.seats.fractional;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Owner-scoped synchronous mutations. Whole-layout installation must also preflight its entire batch. */
public final class FractionalSeatCells {
    private static final Set<ServerLevel> ACTIVE=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private record Snapshot(BlockState state,FractionalSeatData data,BlockState host,BlockEntity tile){}
    private static final class Change{
        final BlockPos pos;final Snapshot before;final FractionalSeatData after;final BlockState target;BlockEntity written;
        Change(BlockPos pos,Snapshot before,FractionalSeatData after){this.pos=pos;this.before=before;this.after=after;target=after.isEmpty()?before.host():cellState(before.host());written=before.tile();}
    }
    private FractionalSeatCells(){}
    public static int install(ServerLevel l,FractionalSeatFootprint p){return replace(l,FractionalSeatFootprint.empty(p.uid()),p);}
    public static int installWithVerifiedSlabHost(ServerLevel l,FractionalSeatFootprint p){return replaceWithVerifiedSlabHost(l,FractionalSeatFootprint.empty(p.uid()),p);}
    public static int remove(ServerLevel l,FractionalSeatFootprint p){return replace(l,p,FractionalSeatFootprint.empty(p.uid()));}
    public static int replace(ServerLevel l,FractionalSeatFootprint a,FractionalSeatFootprint b){return transaction(l,a,b,false);}
    public static int replaceWithVerifiedSlabHost(ServerLevel l,FractionalSeatFootprint a,FractionalSeatFootprint b){return transaction(l,a,b,true);}
    private static int transaction(ServerLevel level,FractionalSeatFootprint a,FractionalSeatFootprint b,boolean allowSlab){
        Objects.requireNonNull(level);Objects.requireNonNull(a);Objects.requireNonNull(b);
        if(!level.getServer().isSameThread())throw new IllegalStateException("Server thread required");
        if(!a.uid().equals(b.uid()))throw new IllegalArgumentException("Cannot transfer UID");
        if(!ACTIVE.add(level))throw new IllegalStateException("Reentrant seat mutation");
        try{return apply(level,a,b,allowSlab);}finally{ACTIVE.remove(level);}
    }
    private static int apply(ServerLevel level,FractionalSeatFootprint old,FractionalSeatFootprint wanted,boolean allowSlab){
        Set<BlockPos> positions=new TreeSet<>(Comparator.comparingLong(BlockPos::asLong));positions.addAll(old.cells().keySet());positions.addAll(wanted.cells().keySet());
        List<Change> all=new ArrayList<>(),writes=new ArrayList<>();UUID uid=old.uid();
        for(BlockPos p:positions){
            if(!level.hasChunkAt(p)||level.isOutsideBuildHeight(p)||!level.getWorldBorder().isWithinBounds(p))throw new IllegalStateException("Unloaded/outside cell: "+p);
            BlockState state=level.getBlockState(p);BlockEntity be=level.getBlockEntity(p);Snapshot before;
            if(state.is(FractionalSeatModule.CELL.get())&&be instanceof FractionalSeatTile tile&&!tile.isRemoved()&&tile.hasValidData()&&FractionalSeatHost.matchesCellState(tile.host(),state))before=new Snapshot(state,tile.data(),tile.host(),tile);
            else if(FractionalSeatHost.allowedInWorld(state,p,level)&&be==null&&(state.isAir()||allowSlab||old.cells().containsKey(p)&&!wanted.cells().containsKey(p)))before=new Snapshot(state,FractionalSeatData.EMPTY,state,null);
            else throw new IllegalStateException("Foreign or malformed host: "+p);
            if(!FractionalSeatHost.allowedInWorld(before.host(),p,level))throw new IllegalStateException("Host not allowed in this world: "+p);
            var expected=old.cells().get(p);var desired=wanted.cells().get(p);var actual=before.data().owner(uid).orElse(null);
            if(!Objects.equals(actual,expected)&&!Objects.equals(actual,desired))throw new IllegalStateException("Stale owner: "+p);
            var next=desired==null?before.data().without(uid):before.data().with(desired);FractionalSeatHost.validateData(next,before.host());
            if(before.host().is(net.minecraft.world.level.block.Blocks.GRAY_CARPET)&&!Original368CarpetContract.validOwners(p,next))throw new IllegalStateException("Foreign carpet owner: "+p);
            Change plan=new Change(p,before,next);all.add(plan);
            if(!next.equals(before.data())||next.isEmpty()&&state.is(FractionalSeatModule.CELL.get()))writes.add(plan);
        }
        List<Change> started=new ArrayList<>();
        try{
            for(Change c:writes){if(!matches(level,c.pos,c.before,true))throw new IllegalStateException("Changed after preflight: "+c.pos);started.add(c);write(level,c);}
            for(Change c:all)if(!owned(level,c,false))throw new IllegalStateException("Final footprint changed: "+c.pos);
            return writes.size();
        }catch(RuntimeException failure){
            for(int i=started.size()-1;i>=0;i--){Change c=started.get(i);try{if(matches(level,c.pos,c.before,true))continue;if(!owned(level,c,true))throw new IllegalStateException("Rollback will not erase foreign edit: "+c.pos);restore(level,c);}catch(RuntimeException secondary){failure.addSuppressed(secondary);}}
            throw failure;
        }
    }
    private static BlockState cellState(BlockState host){return FractionalSeatModule.CELL.get().defaultBlockState().setValue(FractionalSeatBlock.HOST_SLAB,FractionalSeatHost.hasSolidHost(host)).setValue(FractionalSeatBlock.HOST_MATERIAL,FractionalSeatHost.material(host));}
    private static boolean matches(ServerLevel level,BlockPos p,Snapshot expected,boolean identity){
        if(!level.hasChunkAt(p)||!level.getBlockState(p).equals(expected.state()))return false;
        BlockEntity actual=level.getBlockEntity(p);if(expected.tile()==null)return actual==null;
        return (!identity||actual==expected.tile())&&actual instanceof FractionalSeatTile tile&&!tile.isRemoved()&&tile.hasValidData()&&tile.data().equals(expected.data())&&tile.host().equals(expected.host());
    }
    private static boolean initialHost(FractionalSeatTile tile,BlockState retained){return FractionalSeatHost.hasSolidHost(retained)?tile.host().equals(retained):tile.host().is(net.minecraft.world.level.block.Blocks.AIR);}
    private static boolean owned(ServerLevel level,Change c,boolean initialAllowed){
        if(!level.hasChunkAt(c.pos)||!level.getBlockState(c.pos).equals(c.target))return false;
        BlockEntity actual=level.getBlockEntity(c.pos);if(c.after.isEmpty())return actual==null;
        if(actual!=c.written||!(actual instanceof FractionalSeatTile tile)||tile.isRemoved()||!tile.hasValidData())return false;
        return tile.data().equals(c.after)&&tile.host().equals(c.before.host())||initialAllowed&&c.before.tile()==null&&tile.data().isEmpty()&&initialHost(tile,c.before.host());
    }
    private static void write(ServerLevel level,Change c){
        if(!level.getBlockState(c.pos).equals(c.target)&&!level.setBlock(c.pos,c.target,Block.UPDATE_ALL))throw new IllegalStateException("Placement failed: "+c.pos);
        if(!level.getBlockState(c.pos).equals(c.target))throw new IllegalStateException("Callback replaced cell: "+c.pos);
        c.written=level.getBlockEntity(c.pos);
        if(!c.after.isEmpty()){
            if(!(c.written instanceof FractionalSeatTile tile)||tile.isRemoved())throw new IllegalStateException("No live tile: "+c.pos);
            if(c.before.tile()==null&&(!tile.hasValidData()||!tile.data().isEmpty()||!initialHost(tile,c.before.host())))throw new IllegalStateException("Callback claimed new tile: "+c.pos);
            tile.replace(c.after,c.before.host());
        }
        if(!owned(level,c,false))throw new IllegalStateException("Callback changed payload: "+c.pos);
    }
    private static void restore(ServerLevel level,Change c){
        Snapshot s=c.before;boolean recreate=!level.getBlockState(c.pos).equals(s.state());
        if(recreate&&!level.setBlock(c.pos,s.state(),Block.UPDATE_ALL))throw new IllegalStateException("Restoration failed: "+c.pos);
        if(!level.getBlockState(c.pos).equals(s.state()))throw new IllegalStateException("Callback changed restoration: "+c.pos);
        if(s.tile()!=null){
            if(!(level.getBlockEntity(c.pos) instanceof FractionalSeatTile tile)||tile.isRemoved())throw new IllegalStateException("Missing restored tile: "+c.pos);
            if(recreate&&(!tile.hasValidData()||!tile.data().isEmpty()||!initialHost(tile,s.host()))||!recreate&&tile!=c.written)throw new IllegalStateException("Foreign restored payload: "+c.pos);
            tile.replace(s.data(),s.host());
        }
        if(!matches(level,c.pos,s,false))throw new IllegalStateException("Restored data did not persist: "+c.pos);
    }
}
