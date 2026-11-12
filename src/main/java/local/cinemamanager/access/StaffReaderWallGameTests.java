package local.cinemamanager.access;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Native geometry and backward-compatibility checks; no reader authority is granted here. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class StaffReaderWallGameTests {
    private static InteractionResult place(ServerPlayer actor,BlockPos support,Direction face){
        Vec3 point=support.getCenter().add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5);
        return ((BlockItem)AccessModule.READER_ITEM.get()).place(new BlockPlaceContext(new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(point,face,support,false))));
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_reader_wall_item_shape_and_raycast_four_facings(GameTestHelper h){
        ServerPlayer actor=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"ReaderWallQA"));
        actor.setGameMode(GameType.SURVIVAL);BlockPos pos=h.absolutePos(new BlockPos(5,3,5));
        for(Direction facing:Direction.Plane.HORIZONTAL){
            BlockPos support=pos.relative(facing.getOpposite());h.getLevel().setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
            actor.setPos(pos.getCenter().add(facing.getStepX()*2,0,facing.getStepZ()*2));
            actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(AccessModule.READER_ITEM.get(),2));
            h.assertTrue(place(actor,support,facing).consumesAction(),"real item attaches to each sturdy wall face");
            BlockState state=h.getLevel().getBlockState(pos);StaffReaderTile tile=(StaffReaderTile)h.getLevel().getBlockEntity(pos);
            h.assertTrue(state.is(AccessModule.READER.get())&&state.getValue(StaffReaderBlock.FACING)==facing&&tile.owner.equals(actor.getUUID()),"wall placement preserves registered block and ownership and faces outward");
            h.assertTrue(actor.getMainHandItem().getCount()==1,"placement consumes exactly one reader");
            AABB box=state.getShape(h.getLevel(),pos).bounds();
            h.assertTrue(box.getYsize()==.5&&box.minY==.25&&box.maxY==.75,"reader panel is eight pixels high and centered vertically");
            h.assertTrue(box.getXsize()==(facing.getAxis()==Direction.Axis.Z?5.0/16:1.25/16)&&box.getZsize()==(facing.getAxis()==Direction.Axis.Z?1.25/16:5.0/16),"five-pixel width and 1.25-pixel depth rotate together");
            h.assertTrue(switch(facing){case NORTH->box.maxZ==1;case SOUTH->box.minZ==0;case EAST->box.minX==0;case WEST->box.maxX==1;default->false;},"reader back touches the selected wall without extending into it");
            h.assertTrue(state.getCollisionShape(h.getLevel(),pos).bounds().equals(box),"collision matches the thin selectable envelope");
            Vec3 front=pos.getCenter().add(facing.getStepX(),0,facing.getStepZ()),back=support.getCenter();
            BlockHitResult hit=h.getLevel().clip(new ClipContext(front,back,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,actor));
            h.assertTrue(hit.getBlockPos().equals(pos)&&hit.getDirection()==facing,"front ray selects the reading face before the wall");
            for(Rotation rotation:Rotation.values())h.assertTrue(state.rotate(rotation).getValue(StaffReaderBlock.FACING)==rotation.rotate(facing),"structure rotation retains outward facing");
            for(Mirror mirror:Mirror.values())h.assertTrue(state.mirror(mirror).getValue(StaffReaderBlock.FACING)==mirror.mirror(facing),"structure mirroring retains outward facing");
            UUID identity=tile.identity;h.getLevel().setBlockAndUpdate(support,Blocks.AIR.defaultBlockState());
            h.assertTrue(h.getLevel().getBlockEntity(pos)==tile&&tile.identity.equals(identity),"support changes never destroy existing readers or silently invalidate their identity");
            h.getLevel().destroyBlock(pos,false);
        }
        BlockPos floor=pos.below();h.getLevel().setBlockAndUpdate(floor,Blocks.STONE.defaultBlockState());
        actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(AccessModule.READER_ITEM.get(),2));
        h.assertFalse(place(actor,floor,Direction.UP).consumesAction(),"new readers cannot be floor-mounted");
        h.assertFalse(place(actor,floor,Direction.DOWN).consumesAction(),"new readers cannot be ceiling-mounted");
        BlockPos fence=pos.south();h.getLevel().setBlockAndUpdate(fence,Blocks.OAK_FENCE.defaultBlockState());
        h.assertFalse(place(actor,fence,Direction.NORTH).consumesAction(),"new readers need a sturdy wall face");
        h.assertTrue(actor.getMainHandItem().getCount()==2,"rejected attachment never consumes an item");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_reader_legacy_palette_and_binding_nbt_survive_visual_change(GameTestHelper h){
        var registry=h.getLevel().registryAccess();var block=AccessModule.READER.get();
        CompoundTag legacy=new CompoundTag();legacy.putString("Name","mycinema:staff_reader");
        BlockState state=NbtUtils.readBlockState(registry.lookupOrThrow(Registries.BLOCK),legacy);
        h.assertTrue(state==block.defaultBlockState()&&state.getValue(StaffReaderBlock.FACING)==Direction.NORTH,"legacy property-free chunk palette loads the same registered reader with north default");
        h.assertTrue(BlockState.CODEC.parse(NbtOps.INSTANCE,legacy).getOrThrow()==state,"vanilla state codec accepts legacy reader palettes too");
        BlockPos pos=h.absolutePos(new BlockPos(5,3,5));h.getLevel().setBlockAndUpdate(pos,state);
        StaffReaderTile original=(StaffReaderTile)h.getLevel().getBlockEntity(pos);original.initialize(UUID.randomUUID());
        original.controllerPos=pos.west(3);original.controllerId=UUID.randomUUID();original.controllerOwner=UUID.randomUUID();original.doorPos=pos.east(3);original.doorId=UUID.randomUUID();original.required=StaffRole.SERVICE;
        CompoundTag before=original.saveWithoutMetadata(registry);
        for(Direction facing:Direction.Plane.HORIZONTAL){
            BlockState rotated=state.setValue(StaffReaderBlock.FACING,facing);h.getLevel().setBlockAndUpdate(pos,rotated);
            h.assertTrue(h.getLevel().getBlockEntity(pos)==original&&original.saveWithoutMetadata(registry).equals(before),"orientation changes keep the same tile and every binding, revision and ownership field");
            StaffReaderTile restored=new StaffReaderTile(pos,rotated);restored.loadWithComponents(before,registry);
            h.assertTrue(restored.saveWithoutMetadata(registry).equals(before),"old reader NBT round-trips without reinitializing pairing or card revisions");
            h.assertTrue(NbtUtils.readBlockState(registry.lookupOrThrow(Registries.BLOCK),NbtUtils.writeBlockState(rotated))==rotated,"all four new facings round-trip through the palette codec");
        }
        h.getLevel().setBlockAndUpdate(pos.above(),Blocks.STONE.defaultBlockState());h.getLevel().setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        h.assertTrue(h.getLevel().getBlockEntity(pos)==original&&original.saveWithoutMetadata(registry).equals(before),"legacy free-standing reader survives neighbor changes with all old NBT intact");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_reader_removal_retains_cinema_wide_employee_credentials(GameTestHelper h){
        BlockPos pos=h.absolutePos(new BlockPos(5,3,5));h.getLevel().setBlockAndUpdate(pos,AccessModule.READER.get().defaultBlockState());
        StaffReaderTile reader=(StaffReaderTile)h.getLevel().getBlockEntity(pos);reader.initialize(UUID.randomUUID());
        var access=StaffAccessRegistry.get(h.getLevel());UUID employee=UUID.randomUUID(),oldIdentity=reader.identity;
        var scope=new StaffGrantBook.Scope(UUID.randomUUID(),reader.owner,pos.west(3).asLong(),reader.identity,reader.owner,pos.asLong(),UUID.randomUUID(),pos.east(3).asLong(),reader.revision);
        var grant=access.cards.issue(employee,reader.owner,scope,StaffRole.ENTRY);
        h.getLevel().destroyBlock(pos,false);
        h.assertTrue(h.getLevel().getBlockEntity(pos)==null&&reader.isRemoved(),"normal parent cleanup removes the physical reader tile");
        h.assertTrue(access.cards.all().contains(grant),"removing an issuance reader does not delete its cinema-wide employee grant");
        h.getLevel().setBlockAndUpdate(pos,AccessModule.READER.get().defaultBlockState());
        StaffReaderTile replacement=(StaffReaderTile)h.getLevel().getBlockEntity(pos);replacement.initialize(reader.owner);
        h.assertTrue(!replacement.identity.equals(oldIdentity)&&replacement.controllerId==null&&replacement.doorId==null,"a replacement still has fresh identity and no copied pairing");
        h.assertTrue(access.cards.all().contains(grant),"replacement also leaves cinema-wide employee credentials intact");h.succeed();
    }
}
