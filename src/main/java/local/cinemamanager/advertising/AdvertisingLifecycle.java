package local.cinemamanager.advertising;

import local.cinemamanager.*;
import local.cinemamanager.panels.PanelGroup;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.*;
import java.util.function.BiPredicate;

/** Server-thread all-or-nothing geometry replacement. Never writes a foreign or unloaded cell. */
public final class AdvertisingLifecycle {
    private static final Map<Level,Map<BlockPos,Integer>> MUTATING=new WeakHashMap<>();
    private record Saved(BlockState state,CompoundTag tag){}
    private AdvertisingLifecycle(){}
    public static boolean mutating(Level level,BlockPos anchor){return anchor!=null&&MUTATING.getOrDefault(level,Map.of()).containsKey(anchor);}
    static void begin(Level l,BlockPos p){MUTATING.computeIfAbsent(l,k->new HashMap<>()).merge(p.immutable(),1,Integer::sum);}
    static void end(Level l,BlockPos p){var counts=MUTATING.get(l);if(counts!=null){counts.computeIfPresent(p,(key,value)->value==1?null:value-1);if(counts.isEmpty())MUTATING.remove(l);}}

    /** Unknown chunks are never queried. Existing signs defer unknown support; new geometry must prove it. */
    public static boolean supported(LevelReader level,BlockPos anchor,BlockState state,AdvertisingGeometry geometry,boolean requireLoaded){
        if(state.getValue(AdvertisingBlock.FLOOR))return supportedFloor(level,anchor,state,geometry,requireLoaded,null,false);
        Direction facing=state.getValue(AdvertisingBlock.FACING);
        for(BlockPos cell:geometry.cells(anchor,facing))if(!supportedCell(level,anchor,state,geometry,cell,requireLoaded))return false;
        return true;
    }
    /** Item placement has an actor before it has a root tile, including replaceable-cell hit contexts. */
    static boolean supportedForPlacement(LevelReader level,BlockPos anchor,BlockState state,AdvertisingGeometry geometry,UUID owner){
        return state.getValue(AdvertisingBlock.FLOOR)?supportedFloor(level,anchor,state,geometry,true,owner,true)
                :supported(level,anchor,state,geometry,true);
    }
    /** Wall proxies inspect one backing cell. Floor validation also proves its support dependency graph. */
    static boolean supportedCell(LevelReader level,BlockPos anchor,BlockState state,AdvertisingGeometry geometry,BlockPos cell,boolean requireLoaded){
        Direction facing=state.getValue(AdvertisingBlock.FACING);
        boolean floor=state.getValue(AdvertisingBlock.FLOOR);
        if(floor)return supportedFloor(level,anchor,state,geometry,requireLoaded,null,false);
        Direction towardSupport=floor?Direction.DOWN:facing.getOpposite();
        BlockPos support=cell.relative(towardSupport);
        if(level.isOutsideBuildHeight(support))return false;
        if(!level.hasChunkAt(support))return !requireLoaded;
        return level.getBlockState(support).isFaceSturdy(level,support,towardSupport.getOpposite());
    }

    private record FloorBase(BlockPos anchor,AdvertisingGeometry geometry){}
    /** A CHECK lookup cannot create a new identity for a missing or malformed support tile. */
    private static BlockEntity loadedTile(LevelReader level,BlockPos pos){
        if(!level.hasChunkAt(pos))return null;
        var chunk=level.getChunk(pos.getX()>>4,pos.getZ()>>4,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
        if(chunk instanceof LevelChunk full)return full.getBlockEntity(pos,LevelChunk.EntityCreationType.CHECK);
        return chunk==null?null:chunk.getBlockEntity(pos);
    }
    private static InfoTile floorRoot(LevelReader level,BlockPos cell){
        BlockState state=level.getBlockState(cell);BlockEntity tile=loadedTile(level,cell);
        InfoTile root=null;
        if(state.getBlock() instanceof AdvertisingBlock&&tile instanceof InfoTile info)root=info;
        else if(state.is(CinemaManager.AD_PART.get())&&tile instanceof AdvertisingPartTile part
                &&part.getBlockState().equals(state)
                &&part.anchor!=null&&loadedTile(level,part.anchor) instanceof InfoTile)root=part.root();
        return root!=null&&!root.isRemoved()&&root.getLevel()==level
                &&loadedTile(level,root.getBlockPos())==root
                &&level.getBlockState(root.getBlockPos()).equals(root.getBlockState())?root:null;
    }
    /**
     * Only a matching, owned, rectangular top can carry another standing unit. This does not make
     * the thin sign a generally sturdy block. Every dependency descends to a lower bottom edge;
     * visited roots bound shared wide supports and avoid recursive/exponential column checks.
     */
    private static boolean supportedFloor(LevelReader level,BlockPos anchor,BlockState state,AdvertisingGeometry geometry,boolean requireLoaded,UUID placingOwner,boolean placing){
        Direction facing=state.getValue(AdvertisingBlock.FACING),right=facing.getCounterClockWise();
        // Ownership is deliberately absent from public tile packets. Only the server authorizes support.
        boolean client=level instanceof Level world&&world.isClientSide;
        BlockEntity anchorTile=loadedTile(level,anchor);
        boolean knownOwner=placing||anchorTile instanceof InfoTile;
        UUID owner=placing?placingOwner:anchorTile instanceof InfoTile info&&info.isAdvertising()?info.owner:null;
        ArrayDeque<FloorBase> pending=new ArrayDeque<>();Set<BlockPos> examined=new HashSet<>();
        pending.add(new FloorBase(anchor,geometry));examined.add(anchor);
        while(!pending.isEmpty()){
            FloorBase base=pending.removeFirst();AdvertisingGeometry g=base.geometry();
            int bottomY=base.anchor().getY()+Math.floorDiv(g.bottom(),8);
            long bottomEdge=(long)base.anchor().getY()*8+g.bottom();
            long left=planeCoordinate(base.anchor(),right)+g.left(),edge=left+g.width();
            for(int u=Math.floorDiv(g.left(),8);u<=Math.floorDiv(g.right()-1,8);u++){
                BlockPos support=base.anchor().relative(right,u).atY(bottomY-1);
                if(level.isOutsideBuildHeight(support))return false;
                if(!level.hasChunkAt(support)){if(requireLoaded)return false;continue;}
                BlockState below=level.getBlockState(support);
                if(!(below.getBlock() instanceof AdvertisingBlock)&&!below.is(CinemaManager.AD_PART.get())){
                    if(!below.isFaceSturdy(level,support,Direction.UP))return false;
                    continue;
                }
                // An unknown proxy anchor defers existing structures, but may never admit a new one.
                BlockEntity supportingTile=loadedTile(level,support);
                if(supportingTile instanceof AdvertisingPartTile part&&part.anchor!=null&&!level.hasChunkAt(part.anchor)){
                    if(requireLoaded)return false;continue;
                }
                InfoTile root=floorRoot(level,support);
                if(root==null||!root.getBlockState().equals(state)
                        ||!client&&(root.owner==null||knownOwner&&owner==null||owner!=null&&!owner.equals(root.owner))
                        ||!root.advertisingShape.rectangular())return false;
                if(owner==null)owner=root.owner; // BlockItem.canPlace runs before its fresh root has an owner.
                AdvertisingGeometry lower=root.advertising;BlockPos lowerAnchor=root.getBlockPos();
                long top=(long)lowerAnchor.getY()*8+lower.top();
                long lowerLeft=planeCoordinate(lowerAnchor,right)+lower.left();
                long cellLeft=planeCoordinate(support,right);
                if(bottomEdge!=(long)bottomY*8||top!=bottomEdge
                        ||!lower.contains(lowerAnchor,facing,support)
                        ||lowerLeft>Math.max(left,cellLeft)||lowerLeft+lower.width()<Math.min(edge,cellLeft+8))return false;
                if(examined.add(lowerAnchor)){
                    // A support with any loaded missing member is invalid, even when its chosen top is intact.
                    for(BlockPos member:lower.cells(lowerAnchor,facing)){
                        if(member.equals(lowerAnchor))continue;
                        if(!level.hasChunkAt(member)){if(requireLoaded)return false;continue;}
                        BlockState memberState=level.getBlockState(member);
                        if(!memberState.is(CinemaManager.AD_PART.get())
                                ||!(loadedTile(level,member) instanceof AdvertisingPartTile part)
                                ||!part.getBlockState().equals(memberState)||part.root()!=root)return false;
                    }
                    pending.addLast(new FloorBase(lowerAnchor,lower));
                }
            }
        }
        return true;
    }
    private static long planeCoordinate(BlockPos pos,Direction right){return ((long)pos.getX()*right.getStepX()+(long)pos.getZ()*right.getStepZ())*8;}

    public static void resize(InfoTile root,ServerPlayer actor,String signature,AdvertisingGeometry next){
        resizeUsing(root,actor,signature,next,(pos,state)->root.getLevel().setBlock(pos,state,18));
    }

    /** The command supplies only a mode. Collision rows are resolved from this world's approved immutable bytes. */
    public static void applyImageShape(InfoTile root,ServerPlayer actor,String signature,boolean enabled){
        if(enabled&&root.screenMode!=ScreenMode.PICTURE)throw new IllegalArgumentException("mycinema.ad.picture_first");
        AdvertisingShape next=enabled?local.cinemamanager.panels.assets.PanelAssets.advertisingShape(root,root.approvedPoster()):AdvertisingShape.RECTANGLE;
        if(enabled&&next.rectangular())throw new IllegalArgumentException("mycinema.ad.shape_unavailable");
        replaceUsing(root,actor,signature,root.advertising,next,null,(pos,state)->root.getLevel().setBlock(pos,state,18));
    }

    /** A material change and any necessary collision restoration commit or roll back together. */
    public static void replaceForPoster(InfoTile root,ServerPlayer actor,String signature,String nextPoster,Runnable commit){
        AdvertisingShape next=root.advertisingShape.source().equals(local.cinemamanager.panels.assets.AssetReference.normalize(nextPoster))?root.advertisingShape:AdvertisingShape.RECTANGLE;
        replaceUsing(root,actor,signature,root.advertising,next,Objects.requireNonNull(commit),(pos,state)->root.getLevel().setBlock(pos,state,18));
    }

    /** Package-private writer seam permits a real-world partial-failure rollback test. */
    static void resizeUsing(InfoTile root,ServerPlayer actor,String signature,AdvertisingGeometry next,BiPredicate<BlockPos,BlockState> writer){
        replaceUsing(root,actor,signature,next,root.advertisingShape,null,writer);
    }
    static void replaceUsing(InfoTile root,ServerPlayer actor,String signature,AdvertisingGeometry next,AdvertisingShape nextShape,Runnable commit,BiPredicate<BlockPos,BlockState> writer){
        if(!(root.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()
                ||!(root.getBlockState().getBlock() instanceof AdvertisingBlock)||root.isRemoved()
                ||!level.hasChunkAt(root.getBlockPos())||level.getBlockEntity(root.getBlockPos())!=root
                ||actor==null||!root.permits(actor,8))throw new IllegalArgumentException("mycinema.panel.permission");
        if(!PanelGroup.of(root).signature().equals(signature))throw new IllegalArgumentException("mycinema.panel.stale");
        if(next==null||next.width()>DisplayLimits.adSide()*8||next.height()>DisplayLimits.adSide()*8)throw new IllegalArgumentException("mycinema.ad.geometry_error");
        BlockPos anchor=root.getBlockPos();BlockState rootState=root.getBlockState();
        Direction facing=rootState.getValue(AdvertisingBlock.FACING);boolean floor=rootState.getValue(AdvertisingBlock.FLOOR);
        AdvertisingGeometry old=root.advertising;AdvertisingShape oldShape=root.advertisingShape;
        Set<BlockPos> before=new LinkedHashSet<>(old.cells(anchor,facing)),after=new LinkedHashSet<>(next.cells(anchor,facing)),all=new LinkedHashSet<>(before);
        all.addAll(after);
        for(BlockPos pos:all){
            if(!level.hasChunkAt(pos)||level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos))throw new IllegalArgumentException("mycinema.ad.unloaded");
            if(!level.mayInteract(actor,pos))throw new IllegalArgumentException("mycinema.panel.permission");
            if(pos.equals(anchor))continue;
            var tile=level.getBlockEntity(pos);boolean ours=tile instanceof AdvertisingPartTile part&&part.matches(root)
                    &&level.getBlockState(pos).is(CinemaManager.AD_PART.get());
            if(before.contains(pos)&&(!ours||((AdvertisingPartTile)tile).root()!=root))throw new IllegalArgumentException("mycinema.ad.changed");
            if(after.contains(pos)&&!ours&&!level.getBlockState(pos).isAir())throw new IllegalArgumentException("mycinema.ad.obstructed");
        }
        if(!supported(level,anchor,rootState,next,true))throw new IllegalArgumentException("mycinema.ad.support");
        // Test only new physical material. The sign's planar reservation is not a solid full-block wall.
        var added=Shapes.join(nextShape.worldShape(next,anchor,facing,floor),oldShape.worldShape(old,anchor,facing,floor),BooleanOp.ONLY_FIRST);
        if(!level.isUnobstructed(null,added))throw new IllegalArgumentException("mycinema.ad.obstructed");
        if(next.equals(old)&&nextShape.equals(oldShape)&&commit==null)return;
        Map<BlockPos,Saved> saved=new LinkedHashMap<>();
        for(BlockPos pos:all){var tile=level.getBlockEntity(pos);saved.put(pos,new Saved(level.getBlockState(pos),tile==null?null:tile.saveWithFullMetadata(level.registryAccess())));}
        begin(level,anchor);
        try{
            root.advertising=next;root.advertisingShape=nextShape;
            BlockState partState=CinemaManager.AD_PART.get().defaultBlockState().setValue(AdvertisingBlock.FACING,facing).setValue(AdvertisingBlock.FLOOR,floor);
            for(BlockPos pos:after){
                if(pos.equals(anchor))continue;
                if(!level.getBlockState(pos).equals(partState)&&!writer.test(pos,partState))throw new IllegalStateException("placement failed");
                if(!(level.getBlockEntity(pos) instanceof AdvertisingPartTile part))throw new IllegalStateException("part unavailable");
                part.link(root);
            }
            for(BlockPos pos:before)if(!pos.equals(anchor)&&!after.contains(pos)){
                if(!(level.getBlockEntity(pos) instanceof AdvertisingPartTile part)||!part.matches(root)
                        ||!writer.test(pos,Blocks.AIR.defaultBlockState()))throw new IllegalStateException("removal failed");
            }
            if(commit!=null)commit.run();
            root.panelRevision++;root.dirty();
            for(BlockPos pos:all){level.updateNeighborsAt(pos,level.getBlockState(pos).getBlock());level.sendBlockUpdated(pos,saved.get(pos).state(),level.getBlockState(pos),3);}
        }catch(RuntimeException failure){
            root.advertising=old;root.advertisingShape=oldShape;
            // Restore states first and their saved identities/geometry second, without intermediate neighbor updates.
            for(var entry:saved.entrySet())level.setBlock(entry.getKey(),entry.getValue().state(),18);
            for(var entry:saved.entrySet()){
                BlockPos pos=entry.getKey();Saved value=entry.getValue();
                if(value.tag()!=null&&level.getBlockEntity(pos)!=null)level.getBlockEntity(pos).loadWithComponents(value.tag(),level.registryAccess());
                level.sendBlockUpdated(pos,value.state(),value.state(),3);
            }
            root.dirty();throw new IllegalArgumentException("mycinema.ad.changed",failure);
        }finally{end(level,anchor);}
    }

    /** Called after chunk/block-entity load and periodically; unloaded neighbors stay deferred. */
    public static void validateLoaded(InfoTile root){
        if(!(root.getLevel() instanceof ServerLevel level)||mutating(level,root.getBlockPos())
                ||root.isRemoved()||!level.hasChunkAt(root.getBlockPos())||level.getBlockEntity(root.getBlockPos())!=root)return;
        if(!supported(level,root.getBlockPos(),root.getBlockState(),root.advertising,false)){
            level.destroyBlock(root.getBlockPos(),true);return;
        }
        Direction facing=root.getBlockState().getValue(AdvertisingBlock.FACING);
        for(BlockPos pos:root.advertising.cells(root.getBlockPos(),facing)){
            if(pos.equals(root.getBlockPos())||!level.hasChunkAt(pos))continue;
            if(!(level.getBlockEntity(pos) instanceof AdvertisingPartTile part)||part.root()!=root){
                level.destroyBlock(root.getBlockPos(),true);return;
            }
        }
    }

    public static void removeParts(InfoTile root){
        if(!(root.getLevel() instanceof ServerLevel level)||mutating(level,root.getBlockPos()))return;
        BlockPos anchor=root.getBlockPos();Direction facing=root.getBlockState().getValue(AdvertisingBlock.FACING);begin(level,anchor);
        try{for(BlockPos pos:root.advertising.cells(anchor,facing))if(!pos.equals(anchor)&&level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof AdvertisingPartTile part&&part.matches(root))level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}
        finally{end(level,anchor);}
    }
}
