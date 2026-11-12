package local.cinemamanager.advertising;

import local.cinemamanager.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiPredicate;

/** Explicit, reviewable assembly of compatible whole-metre units. Existing content is never discarded. */
public final class AdvertisingAssembly {
    public record Plan(List<InfoTile> units,AdvertisingGeometry geometry,String signature){
        public Plan{units=List.copyOf(units);}
    }
    private record Saved(BlockState state,CompoundTag tag){}
    private AdvertisingAssembly(){}
    private static boolean compatible(InfoTile root,InfoTile unit){
        return unit!=null&&!unit.isRemoved()&&unit.isAdvertising()&&unit.getBlockState().equals(root.getBlockState())
                &&unit.getLevel()==root.getLevel()&&root.owner!=null&&root.owner.equals(unit.owner)
                &&unit.advertising.equals(AdvertisingGeometry.DEFAULT)&&unit.advertisingShape.rectangular()&&unit.assembledUnits==1
                &&unit.panel.equals(root.panel)&&unit.screenMode==root.screenMode&&unit.scope==root.scope
                &&Objects.equals(unit.manager,root.manager)&&Objects.equals(unit.managerIdentity,root.managerIdentity)
                &&Objects.equals(unit.hallId,root.hallId)&&Objects.equals(unit.mirror,root.mirror)
                &&Objects.equals(unit.mirrorBoardOwner,root.mirrorBoardOwner)
                &&Objects.equals(unit.approvedPoster(),root.approvedPoster());
    }
    public static Plan inspect(InfoTile root){
        if(!(root.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()
                ||!level.hasChunkAt(root.getBlockPos())||level.getBlockEntity(root.getBlockPos())!=root||!compatible(root,root))return null;
        Direction right=root.getBlockState().getValue(AdvertisingBlock.FACING).getCounterClockWise();
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();Set<BlockPos> examined=new HashSet<>();List<InfoTile> units=new ArrayList<>();queue.add(root.getBlockPos());
        while(!queue.isEmpty()){
            BlockPos pos=queue.removeFirst();if(!examined.add(pos)||!level.hasChunkAt(pos))continue;
            if(!(level.getBlockEntity(pos) instanceof InfoTile unit)||!compatible(root,unit))continue;
            if(units.size()>=256)return null;units.add(unit);
            queue.add(pos.above());queue.add(pos.below());queue.add(pos.relative(right));queue.add(pos.relative(right.getOpposite()));
        }
        if(units.size()<2)return null;
        int minU=0,maxU=0,minV=0,maxV=0;BlockPos anchor=root.getBlockPos();
        for(InfoTile unit:units){BlockPos d=unit.getBlockPos().subtract(anchor);int u=d.getX()*right.getStepX()+d.getZ()*right.getStepZ();minU=Math.min(minU,u);maxU=Math.max(maxU,u);minV=Math.min(minV,d.getY());maxV=Math.max(maxV,d.getY());}
        int width=maxU-minU+1,height=maxV-minV+1;
        if(width>DisplayLimits.adSide()||height>DisplayLimits.adSide()||width*height!=units.size())return null;
        units.sort(Comparator.comparingLong(unit->unit.getBlockPos().asLong()));
        StringBuilder signature=new StringBuilder("ad-assembly-v1:").append(root.identity).append(':');
        for(InfoTile unit:units)signature.append(unit.getBlockPos().asLong()).append('/').append(unit.identity).append('/').append(unit.panelRevision).append(';');
        return new Plan(units,new AdvertisingGeometry(minU*8,minV*8,width*8,height*8),UUID.nameUUIDFromBytes(signature.toString().getBytes(StandardCharsets.UTF_8)).toString());
    }
    public static void assemble(InfoTile root,ServerPlayer actor,String expected){assembleUsing(root,actor,expected,(pos,state)->root.getLevel().setBlock(pos,state,18));}
    static void assembleUsing(InfoTile root,ServerPlayer actor,String expected,BiPredicate<BlockPos,BlockState> writer){
        if(actor==null||!root.permits(actor,8))throw new IllegalArgumentException("mycinema.panel.permission");
        Plan plan=inspect(root);if(plan==null||!plan.signature().equals(expected))throw new IllegalArgumentException("mycinema.panel.stale");
        ServerLevel level=(ServerLevel)root.getLevel();BlockPos anchor=root.getBlockPos();BlockState rootState=root.getBlockState();
        Direction facing=rootState.getValue(AdvertisingBlock.FACING);boolean floor=rootState.getValue(AdvertisingBlock.FLOOR);
        VoxelShape before=Shapes.empty();Map<BlockPos,Saved> saved=new LinkedHashMap<>();
        for(InfoTile unit:plan.units()){
            if(!unit.permits(actor,Double.POSITIVE_INFINITY)||!level.mayInteract(actor,unit.getBlockPos()))throw new IllegalArgumentException("mycinema.panel.permission");
            if(!level.getWorldBorder().isWithinBounds(unit.getBlockPos()))throw new IllegalArgumentException("mycinema.ad.unloaded");
            before=Shapes.or(before,unit.advertising.worldShape(unit.getBlockPos(),facing,floor));
            saved.put(unit.getBlockPos(),new Saved(unit.getBlockState(),unit.saveWithFullMetadata(level.registryAccess())));
        }
        if(!AdvertisingLifecycle.supported(level,anchor,rootState,plan.geometry(),true))throw new IllegalArgumentException("mycinema.ad.support");
        if(!level.isUnobstructed(null,Shapes.join(plan.geometry().worldShape(anchor,facing,floor),before,BooleanOp.ONLY_FIRST)))throw new IllegalArgumentException("mycinema.ad.obstructed");
        for(BlockPos pos:saved.keySet())AdvertisingLifecycle.begin(level,pos);
        try{
            root.advertising=plan.geometry();root.assembledUnits=plan.units().size();root.panelRevision++;
            BlockState proxy=CinemaManager.AD_PART.get().defaultBlockState().setValue(AdvertisingBlock.FACING,facing).setValue(AdvertisingBlock.FLOOR,floor);
            for(InfoTile unit:plan.units()){
                BlockPos pos=unit.getBlockPos();if(pos.equals(anchor))continue;
                if(!writer.test(pos,proxy)||!(level.getBlockEntity(pos) instanceof AdvertisingPartTile part))throw new IllegalStateException("proxy placement failed");
                part.link(root);
            }
            root.refreshNow();root.dirty();
            for(BlockPos pos:saved.keySet()){level.updateNeighborsAt(pos,level.getBlockState(pos).getBlock());level.sendBlockUpdated(pos,saved.get(pos).state(),level.getBlockState(pos),3);}
        }catch(RuntimeException failure){
            for(var entry:saved.entrySet())level.setBlock(entry.getKey(),entry.getValue().state(),18);
            for(var entry:saved.entrySet())if(level.getBlockEntity(entry.getKey())!=null){level.getBlockEntity(entry.getKey()).loadWithComponents(entry.getValue().tag(),level.registryAccess());level.sendBlockUpdated(entry.getKey(),entry.getValue().state(),entry.getValue().state(),3);}
            throw new IllegalArgumentException("mycinema.ad.changed",failure);
        }finally{for(BlockPos pos:saved.keySet())AdvertisingLifecycle.end(level,pos);}
    }
}
