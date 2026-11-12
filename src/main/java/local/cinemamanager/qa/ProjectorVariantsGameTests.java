package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.*;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.projection.CinemaProjectorItem;
import local.cinemamanager.projection.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Pending native validation. Compile separately; never included in the production WaterFrames JAR. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ProjectorVariantsGameTests {
    private static final class Actor extends FakePlayer {
        Actor(ServerLevel level){super(level,new GameProfile(UUID.randomUUID(),"WFVariantFixture"));setGameMode(GameType.SURVIVAL);}
    }
    private static BlockPos anchor(GameTestHelper h){return h.absolutePos(new BlockPos(4,2,4));}
    private static Actor actor(GameTestHelper h,Direction facing){var actor=new Actor(h.getLevel());var p=anchor(h);actor.setPos(p.getX()+.5,p.getY(),p.getZ()-3);actor.setYRot(facing.toYRot());return actor;}
    private static void floor(GameTestHelper h){var p=anchor(h);for(int x=-3;x<=5;x++)for(int z=-3;z<=3;z++)h.getLevel().setBlockAndUpdate(p.offset(x,-1,z),Blocks.STONE.defaultBlockState());}
    private static void clear(GameTestHelper h){var p=anchor(h);for(int x=-3;x<=5;x++)for(int y=0;y<=2;y++)for(int z=-3;z<=3;z++)h.getLevel().setBlock(p.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);for(var item:items(h))item.discard();}
    private static List<ItemEntity> items(GameTestHelper h){return h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(anchor(h)).inflate(4),e->e.getItem().is(CinemaDisplays.CINEMA_PROJECTOR_ITEM.get())||e.getItem().is(CinemaDisplays.IMAX_GT_PROJECTOR_ITEM.get()));}
    private static ItemStack place(GameTestHelper h,Actor actor,ProjectorStyle style){
        ItemStack stack=CinemaProjectorItem.stack(style);stack.setCount(2);actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var context=new BlockPlaceContext(h.getLevel(),actor,InteractionHand.MAIN_HAND,stack,new BlockHitResult(Vec3.atCenterOf(anchor(h)),Direction.UP,anchor(h),false));
        h.assertTrue(stack.useOn(context).consumesAction(),"one item action places "+style);h.assertTrue(stack.getCount()==1,"exactly one item consumed");return stack;
    }
    private static void verifyCells(GameTestHelper h,ProjectorStyle style,Direction facing){
        var world=h.getLevel();var p=anchor(h);var state=world.getBlockState(p);
        var expectedBlock=style==ProjectorStyle.IMAX_GT?CinemaDisplays.IMAX_GT_PROJECTOR.get():CinemaDisplays.CINEMA_PROJECTOR.get();
        h.assertTrue(state.is(expectedBlock)&&world.getBlockEntity(p) instanceof CinemaProjectorTile,"style has its own MyCinema registry ID and device tile");
        var projector=(CinemaProjectorTile)world.getBlockEntity(p);h.assertTrue(projector.style()==style&&projector.machineFacing()==facing,"requested style and facing persist on the owned device");
        var pick=CinemaProjectorItem.stack(style);h.assertTrue(pick.getCount()==1&&CinemaProjectorItem.style(pick)==style,"sanitized pick representation retains style");
        h.assertTrue(pick.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA)==null,"pick carries no owner/link/media identity");
        h.assertTrue(world.getBlockEntity(p) instanceof CinemaProjectorTile,"grouped style has shared-media member tile");
        for(var entry:ProjectorGeometry.cells(style,facing).entrySet()){
            var pos=p.offset(entry.getKey());var box=entry.getValue().toAabbs().getFirst().move(pos);var center=box.getCenter();
            var probe=new AABB(center.x-.001,center.y-.001,center.z-.001,center.x+.001,center.y+.001,center.z+.001);
            h.assertTrue(world.getBlockCollisions(null,probe).iterator().hasNext(),"real AABB collision in every occupied cell");
            if(!entry.getKey().equals(BlockPos.ZERO)){
                h.assertFalse(world.getBlockState(pos).canOcclude(),"invisible cabinet part does not cull the visible floor beneath it");
                h.assertTrue(ProjectorStructure.resolve(world,pos).equals(p),"part routes to anchor");
                h.assertTrue(CinemaProjectorItem.style(world.getBlockState(pos).getBlock().getCloneItemStack(world,pos,world.getBlockState(pos)))==style,"part pick preserves style");
                var hit=world.clip(new ClipContext(center.add(0,3,0),center,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()));
                h.assertTrue(hit.getType()==HitResult.Type.BLOCK&&!hit.getBlockPos().equals(p)&&ProjectorStructure.resolve(world,hit.getBlockPos()).equals(p),"off-anchor ray targets whole machine");
            }
        }
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void projector_three_styles_four_facings(GameTestHelper h){
        floor(h);for(var facing:Direction.Plane.HORIZONTAL)for(var style:CinemaProjectorItem.creativeStyles()){clear(h);var actor=actor(h,facing);place(h,actor,style);verifyCells(h,style,facing);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void projector_every_part_one_survival_drop(GameTestHelper h){
        floor(h);for(var facing:Direction.Plane.HORIZONTAL)for(var offset:ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,facing).keySet()){
            clear(h);var actor=actor(h,facing);place(h,actor,ProjectorStyle.IMAX_GT);actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_PICKAXE));
            actor.gameMode.destroyBlock(anchor(h).offset(offset));
            h.assertTrue(h.getLevel().getBlockState(anchor(h)).isAir(),"breaking any occupied cell removes anchor");
            int total=items(h).stream().mapToInt(e->e.getItem().getCount()).sum();h.assertTrue(total==1,"exactly one item for survival break");
            h.assertTrue(items(h).stream().allMatch(e->CinemaProjectorItem.style(e.getItem())==ProjectorStyle.IMAX_GT),"drop retains IMAX GT style");
            for(var cell:ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,facing).keySet())h.assertTrue(h.getLevel().getBlockState(anchor(h).offset(cell)).isAir(),"no leftover collision cells");
        }h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void projector_entity_blockage_is_atomic(GameTestHelper h){
        floor(h);clear(h);var actor=actor(h,Direction.NORTH);var p=anchor(h);
        // A real blocking entity is outside the anchor, inside the cabinet's right-hand cell.
        var pig=EntityType.PIG.create(h.getLevel());pig.setPos(p.getX()+1.1,p.getY()+.1,p.getZ()+.6);pig.setNoAi(true);h.getLevel().addFreshEntity(pig);
        var stack=CinemaProjectorItem.stack(ProjectorStyle.IMAX_GT);stack.setCount(2);actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var context=new BlockPlaceContext(h.getLevel(),actor,InteractionHand.MAIN_HAND,stack,new BlockHitResult(Vec3.atCenterOf(p),Direction.UP,p,false));
        h.assertTrue(stack.useOn(context)==InteractionResult.FAIL&&stack.getCount()==2,"blocked placement fails without item consumption");
        for(var cell:ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,Direction.NORTH).keySet())h.assertTrue(h.getLevel().getBlockState(p.offset(cell)).isAir(),"blocked placement leaves no cells");
        pig.discard();
        // The placing actor must not be excluded from entity clearance.
        actor.setPos(p.getX()+1.1,p.getY()+.1,p.getZ()+.6);h.getLevel().addFreshEntity(actor);
        h.assertTrue(stack.useOn(context)==InteractionResult.FAIL&&stack.getCount()==2,"placing player also blocks a peripheral cell");actor.discard();
        var cancelActor=actor(h,Direction.NORTH);var cancelStack=CinemaProjectorItem.stack(ProjectorStyle.IMAX_GT);cancelStack.setCount(2);cancelActor.setItemInHand(InteractionHand.MAIN_HAND,cancelStack);
        var grassPos=p.offset(1,0,1);h.getLevel().setBlockAndUpdate(grassPos,Blocks.SHORT_GRASS.defaultBlockState());
        int[] cancelled={0};
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> listener=event->{
            if(event.getEntity()==cancelActor){
                h.assertTrue(event instanceof net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent,"one action emits multi-place event");
                var snapshots=((net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent)event).getReplacedBlockSnapshots();
                h.assertTrue(snapshots.size()==ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,Direction.NORTH).size(),"every parallel-cabinet write enters the ordinary snapshots");
                var grassSnapshot=snapshots.stream().filter(snapshot->snapshot.getPos().equals(grassPos)).findFirst().orElse(null);
                h.assertTrue(grassSnapshot!=null&&grassSnapshot.getState().is(Blocks.SHORT_GRASS),"grass snapshot records original state; snapshot="+(grassSnapshot==null?"missing":grassSnapshot.getState())+" below="+h.getLevel().getBlockState(grassPos.below()));
                cancelled[0]++;event.setCanceled(true);
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent.class,listener);
        try {
            var cancelledContext=new BlockPlaceContext(h.getLevel(),cancelActor,InteractionHand.MAIN_HAND,cancelStack,new BlockHitResult(Vec3.atCenterOf(p),Direction.UP,p,false));
            h.assertTrue(cancelStack.useOn(cancelledContext)==InteractionResult.FAIL&&cancelStack.getCount()==2&&cancelled[0]==1,"protection cancellation restores original inventory count");
            for(var cell:ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,Direction.NORTH).keySet()){
                var worldPos=p.offset(cell);var restored=h.getLevel().getBlockState(worldPos);var tile=h.getLevel().getBlockEntity(worldPos);
                h.assertTrue(restored.is(worldPos.equals(grassPos)?Blocks.SHORT_GRASS:Blocks.AIR)&&tile==null,"cancellation restores "+worldPos+" expected="+(worldPos.equals(grassPos)?"short_grass":"air")+" actual="+restored+" tile="+tile);
            }
            h.assertTrue(items(h).isEmpty(),"cancelled placement produces no dropped duplicate");
        } finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void projector_chunk_nbt_keeps_one_tms_session(GameTestHelper h){
        floor(h);clear(h);var actor=actor(h,Direction.NORTH);place(h,actor,ProjectorStyle.IMAX_GT);var p=anchor(h);var world=h.getLevel();
        var terminalPos=p.offset(0,0,-2);world.setBlockAndUpdate(terminalPos,CinemaDisplays.TMS_TERMINAL.get().defaultBlockState());
        var terminal=(CinemaTerminalTile)world.getBlockEntity(terminalPos);terminal.owner(actor.getUUID());
        var secondPos=p.offset(4,0,0);var secondStack=CinemaProjectorItem.stack(ProjectorStyle.CINEMA);secondStack.setCount(2);
        var secondContext=new BlockPlaceContext(world,actor,InteractionHand.MAIN_HAND,secondStack,new BlockHitResult(Vec3.atCenterOf(secondPos),Direction.UP,secondPos,false));
        h.assertTrue(secondStack.useOn(secondContext).consumesAction(),"Cinema variant places alongside IMAX GT");
        var second=(CinemaProjectorTile)world.getBlockEntity(secondPos);
        h.assertTrue(terminal.bindNearby(actor)==2,"both native variants bind to the same TMS");var projector=(CinemaProjectorTile)world.getBlockEntity(p);var id=projector.identity();
        // Exercise actual vanilla chunk serialization without evicting the GameTest's forced-loaded chunk.
        Map<ChunkPos,net.minecraft.world.level.chunk.ProtoChunk> reloaded=new HashMap<>();
        for(var offset:ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,Direction.NORTH).keySet()){
            var cell=p.offset(offset);var chunkPos=new ChunkPos(cell);
            var restored=reloaded.computeIfAbsent(chunkPos,key->{var tag=ChunkSerializer.write(world,world.getChunkAt(cell));var loaded=ChunkSerializer.read(world,world.getPoiManager(),new RegionStorageInfo("wf-variant-test",world.dimension(),"chunk"),key,tag);
                // Full chunks defer BE reconstruction until their normal post-load phase.
                if(loaded instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter)imposter.getWrapped().runPostLoad();
                return loaded;});
            var state=restored.getBlockState(cell);CompoundTag nbt=restored.getBlockEntityNbtForSaving(cell,world.registryAccess());
            h.assertTrue(nbt!=null,"chunk contains persistent device/part NBT");var tile=BlockEntity.loadStatic(cell,state,nbt,world.registryAccess());h.assertTrue(tile!=null,"native registry reconstructs serialized tile");
            if(offset.equals(BlockPos.ZERO)){var main=(CinemaProjectorTile)tile;main.setLevel(world);h.assertTrue(main.identity().equals(id)&&main.linkedTo(terminal),"anchor identity and TMS backlink survive chunk codec");h.assertTrue(main.clock()==null&&main.follower()==null,"restored member owns no clock/decoder");}
            else{var part=(CinemaProjectorPartTile)tile;h.assertTrue(part.belongsTo(p,id),"cell anchor and UUID survive chunk codec");}
        }
        h.assertTrue(projector.clock()==null&&projector.follower()==null&&projector.terminal()==terminal&&second.clock()==null&&second.follower()==null&&second.terminal()==terminal,"both variants share TMS and own no playback resources");
        terminal.data.setUrl("water://projector-variants-offline-qa");terminal.data.active=true;terminal.data.paused=false;
        h.runAfterDelay(5,()->{
            h.assertTrue(terminal.clock()!=null&&terminal.clock().playing(),"one real TMS server clock starts");var clock=terminal.clock();long before=clock.time();
            h.runAfterDelay(15,()->{
                h.assertTrue(terminal.clock()==clock&&clock.time()>before,"same TMS clock advances across ticks");
                h.assertTrue(projector.terminal()==terminal&&second.terminal()==terminal&&projector.clock()==null&&second.clock()==null&&projector.follower()==null&&second.follower()==null,"neither variant creates a second clock or decoder");
                clock.stop();h.assertTrue(clock.stopped()&&terminal.clock()==clock,"stop retains the unique shared session");h.succeed();
            });
        });
    }
}
