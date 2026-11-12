package local.cinemamanager.seats;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Real item, shape, support and persistence checks for the independent floor decal. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class SeatGuideFloorGameTests {
    private static ServerPlayer actor(GameTestHelper h){
        ServerPlayer actor=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"FloorGuideQA"));
        actor.setGameMode(GameType.SURVIVAL);return actor;
    }
    private static InteractionResult place(ServerPlayer actor,BlockPos support,Direction face){
        double top=actor.level().getBlockState(support).getShape(actor.level(),support).max(Direction.Axis.Y);
        Vec3 point=face==Direction.UP?new Vec3(support.getX()+.5,support.getY()+top,support.getZ()+.5)
            :support.getCenter().add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5);
        return ((BlockItem)SeatsModule.GUIDE_ITEM.get()).place(new BlockPlaceContext(new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(point,face,support,false))));
    }
    private static void nearby(ServerPlayer actor,BlockPos pos,Direction facing){
        actor.setPos(pos.getCenter().add(facing.getStepX()*2,0,facing.getStepZ()*2));actor.setYRot(facing.getOpposite().toYRot());
    }
    private static int dropped(GameTestHelper h,BlockPos pos){
        return h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1)).stream().filter(e->e.getItem().is(SeatsModule.GUIDE_ITEM.get())).mapToInt(e->e.getItem().getCount()).sum();
    }
    private static GuidePreset preset(){return new GuidePreset("Hall 1","Upper",1,8,0,GuidePreset.Arrow.UP_RIGHT,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,1,12);}

    @GameTest(template="empty",timeoutTicks=50)
    public static void seat_guide_floor_real_item_four_facings_and_supported_slab_tops(GameTestHelper h){
        ServerPlayer owner=actor(h);BlockPos pos=h.absolutePos(new BlockPos(5,3,5)),support=pos.below();
        BlockState[] supports={Blocks.STONE.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE)};
        for(BlockState backing:supports)for(Direction facing:Direction.Plane.HORIZONTAL){
            h.getLevel().setBlockAndUpdate(support,backing);nearby(owner,pos,facing);owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.GUIDE_ITEM.get(),2));
            h.assertTrue(place(owner,support,Direction.UP).consumesAction(),"real guide item attaches to a solid full-height top, including top/double slabs");
            h.assertTrue(owner.getMainHandItem().getCount()==1,"accepted floor placement consumes exactly one tag");
            SeatGuideTile guide=(SeatGuideTile)h.getLevel().getBlockEntity(pos);BlockState state=guide.getBlockState();
            h.assertTrue(state.getValue(SeatGuideBlock.FLOOR)&&state.getValue(SeatGuideBlock.FACING)==facing&&guide.owner.equals(owner.getUUID()),"floor flag, cardinal reading direction and owner come from item placement");
            h.assertTrue(state.canSurvive(h.getLevel(),pos),"accepted floor tag has physical support immediately beneath it");
            AABB bounds=state.getShape(h.getLevel(),pos).bounds();
            h.assertTrue(bounds.minY==0&&bounds.maxY==1.0/32&&bounds.getXsize()*bounds.getZsize()==.125,"selection is a horizontal 8 by 4 by half-pixel decal touching the floor");
            h.assertTrue(bounds.getXsize()==(facing.getAxis()==Direction.Axis.Z?.5:.25)&&bounds.getZsize()==(facing.getAxis()==Direction.Axis.Z?.25:.5),"cardinal rotation turns the selection footprint together with the label");
            h.assertTrue(state.getCollisionShape(h.getLevel(),pos).isEmpty()&&h.getLevel().noCollision(new AABB(pos.getX()+.1,pos.getY()+.001,pos.getZ()+.1,pos.getX()+.9,pos.getY()+1.8,pos.getZ()+.9)),"walking through the decal adds no collision above the floor");
            Vec3 start=pos.getCenter().add(0,1,0),end=pos.getCenter().add(0,-1,0);
            BlockHitResult hit=h.getLevel().clip(new ClipContext(start,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,owner));
            h.assertTrue(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(pos)&&hit.getDirection()==Direction.UP&&Math.abs(hit.getLocation().y-pos.getY()-1.0/32)<.00001,"ordinary crosshair ray selects the decal top instead of the floor block below");
            for(Rotation rotation:Rotation.values()){
                BlockState rotated=state.rotate(rotation);h.assertTrue(rotated.getValue(SeatGuideBlock.FLOOR)&&rotated.getValue(SeatGuideBlock.FACING)==rotation.rotate(facing),"structure rotation preserves floor attachment and rotates the complete text plane");
            }
            for(Mirror mirror:Mirror.values()){
                BlockState mirrored=state.mirror(mirror);h.assertTrue(mirrored.getValue(SeatGuideBlock.FLOOR)&&mirrored.getValue(SeatGuideBlock.FACING)==mirror.mirror(facing),"structure mirror preserves floor attachment and cardinal facing");
            }
            h.getLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(support,Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void seat_guide_floor_rejects_gaps_and_undersides_and_drops_on_support_loss(GameTestHelper h){
        ServerPlayer owner=actor(h);BlockPos pos=h.absolutePos(new BlockPos(5,4,5)),support=pos.below();nearby(owner,pos,Direction.NORTH);
        for(BlockState backing:new BlockState[]{Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM),Blocks.OAK_FENCE.defaultBlockState()}){
            h.getLevel().setBlockAndUpdate(support,backing);owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.GUIDE_ITEM.get(),2));
            h.assertFalse(place(owner,support,Direction.UP).consumesAction(),"lower slabs and incomplete top faces cannot create floating floor guides");
            h.assertTrue(owner.getMainHandItem().getCount()==2&&h.getLevel().getBlockState(pos).isAir()&&h.getLevel().getBlockState(support)==backing,"rejected support preserves item count and both world cells");
        }
        h.getLevel().setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
        h.assertFalse(place(owner,support,Direction.DOWN).consumesAction(),"underside attachment is still unsupported");
        h.assertTrue(owner.getMainHandItem().getCount()==2,"rejected underside does not consume a guide");
        h.assertTrue(place(owner,support,Direction.UP).consumesAction(),"valid supported placement still works after rejected attempts");
        int before=dropped(h,pos);h.getLevel().setBlockAndUpdate(support,Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM));
        h.assertTrue(h.getLevel().getBlockState(pos).isAir()&&h.getLevel().getBlockEntity(pos)==null&&dropped(h,pos)==before+1,"lowering the supporting surface removes the decal and drops exactly one tag");
        h.getLevel().setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());h.assertTrue(place(owner,support,Direction.UP).consumesAction(),"remaining guide can be placed again after restoring support");
        before=dropped(h,pos);h.getLevel().destroyBlock(support,false);
        h.assertTrue(h.getLevel().getBlockState(pos).isAir()&&h.getLevel().getBlockEntity(pos)==null&&dropped(h,pos)==before+1,"removing support drops one guide and leaves no floating block entity");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void seat_guide_floor_copy_sync_chunk_roundtrip_and_legacy_wall_state(GameTestHelper h){
        ServerPlayer owner=actor(h);BlockPos floorPos=h.absolutePos(new BlockPos(5,3,5)),wallPos=floorPos.east(2);
        h.getLevel().setBlockAndUpdate(floorPos.below(),Blocks.STONE.defaultBlockState());nearby(owner,floorPos,Direction.WEST);
        owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.GUIDE_ITEM.get(),3));
        h.assertTrue(place(owner,floorPos.below(),Direction.UP).consumesAction(),"floor source tag places through actual item path");
        SeatGuideTile floor=(SeatGuideTile)h.getLevel().getBlockEntity(floorPos);floor.update(owner,0,preset());floor.copyTo(owner,1);
        GuidePreset copied=SeatRegistry.JSON.fromJson(owner.getMainHandItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("mycinema_guide"),GuidePreset.class);
        h.assertTrue(copied.equals(preset())&&copied.displayLabel().equals("A-H · S1-12"),"copy keeps unprefixed letters, both numeric ranges and manually chosen arrow");
        h.getLevel().setBlockAndUpdate(wallPos.south(),Blocks.STONE.defaultBlockState());nearby(owner,wallPos,Direction.NORTH);
        h.assertTrue(place(owner,wallPos.south(),Direction.NORTH).consumesAction(),"copied floor content can still be placed on a wall");
        SeatGuideTile wall=(SeatGuideTile)h.getLevel().getBlockEntity(wallPos);
        h.assertTrue(!wall.getBlockState().getValue(SeatGuideBlock.FLOOR)&&wall.getBlockState().getValue(SeatGuideBlock.FACING)==Direction.NORTH&&wall.preset.equals(preset()),"copy transfers content while the clicked face selects attachment and facing");
        for(Direction facing:Direction.Plane.HORIZONTAL){
            BlockState expected=wall.getBlockState().setValue(SeatGuideBlock.FACING,facing);
            CompoundTag legacy=NbtUtils.writeBlockState(expected);legacy.getCompound("Properties").remove("floor");
            BlockState legacyState=NbtUtils.readBlockState(h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK),legacy);
            h.assertTrue(legacyState==expected&&BlockState.CODEC.parse(NbtOps.INSTANCE,legacy).getOrThrow()==expected,"old wall states lacking the floor property preserve every facing in both vanilla state readers");
        }
        for(SeatGuideTile original:new SeatGuideTile[]{floor,wall}){
            SeatGuideTile client=new SeatGuideTile(original.getBlockPos(),original.getBlockState());client.loadWithComponents(original.getUpdateTag(h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(client.preset.equals(preset())&&client.revision==original.revision&&client.getBlockState()==original.getBlockState(),"update payload and block state give clients matching content and text orientation");
            var world=h.getLevel();BlockPos pos=original.getBlockPos();CompoundTag chunkTag=ChunkSerializer.write(world,world.getChunkAt(pos));
            ProtoChunk restored=ChunkSerializer.read(world,world.getPoiManager(),new RegionStorageInfo("mycinema-floor-guide-test",world.dimension(),"chunk"),new ChunkPos(pos),chunkTag);
            if(restored instanceof ImposterProtoChunk imposter)imposter.getWrapped().runPostLoad();
            CompoundTag saved=restored.getBlockEntityNbtForSaving(pos,world.registryAccess());h.assertTrue(saved!=null,"native chunk save retains floor and wall guide NBT");
            BlockEntity entity=BlockEntity.loadStatic(pos,restored.getBlockState(pos),saved,world.registryAccess());
            h.assertTrue(entity instanceof SeatGuideTile,"native registry reconstructs the saved guide entity");SeatGuideTile loaded=(SeatGuideTile)entity;
            h.assertTrue(loaded.getBlockState()==original.getBlockState()&&loaded.preset.equals(preset())&&loaded.owner.equals(owner.getUUID())&&loaded.identity.equals(original.identity)&&loaded.revision==original.revision,"chunk codec preserves attachment, facing, identity, owner, revision and exact row/seat/arrow preset");
            h.assertTrue(Block.getDrops(original.getBlockState(),world,pos,original).stream().filter(stack->stack.is(SeatsModule.GUIDE_ITEM.get())).mapToInt(ItemStack::getCount).sum()==1,"both attachments retain the existing one-tag survival loot");
        }
        int before=dropped(h,floorPos);h.getLevel().destroyBlock(floorPos,true);
        h.assertTrue(h.getLevel().getBlockState(floorPos).isAir()&&h.getLevel().getBlockEntity(floorPos)==null&&dropped(h,floorPos)==before+1,"breaking the selected floor tag drops it exactly once and removes its entity");
        h.assertTrue(h.getLevel().getBlockState(floorPos.below()).is(Blocks.STONE)&&h.getLevel().getBlockEntity(wallPos)==wall,"breaking a floor decal leaves its support and neighboring wall guide intact");
        h.succeed();
    }
}
