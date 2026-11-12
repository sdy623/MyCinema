package local.cinemamanager.seats;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Native server acceptance sources. These tests must run in an independent QA world. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class CinemaSeatGameTests {
    private static ServerPlayer actor(GameTestHelper h) {
        // NeoForge FakePlayer deliberately refuses startRiding, so physical passenger acceptance
        // must exercise ServerPlayer itself. Only transport is stubbed; vanilla mount/dismount,
        // dimensions, reach, game mode, placement and break logic all remain intact.
        ServerPlayer player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),
            new GameProfile(UUID.randomUUID(),"PhysicalSeatQA"),ClientInformation.createDefault());
        player.connection=new ServerGamePacketListenerImpl(h.getLevel().getServer(),
            new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(player.getGameProfile(),false)) {
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet,PacketSendListener listener) {}
        };
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }
    private static BlockPos area(GameTestHelper h) {
        BlockPos pos=h.absolutePos(new BlockPos(5,3,5));
        for (BlockPos floor:BlockPos.betweenClosed(pos.offset(-3,-1,-3),pos.offset(3,-1,3)))
            h.getLevel().setBlockAndUpdate(floor,Blocks.STONE.defaultBlockState());
        return pos;
    }
    private static void near(ServerPlayer player,BlockPos pos,Direction face) {
        player.setPos(pos.getX()+.5+face.getStepX()*2,pos.getY(),pos.getZ()+.5+face.getStepZ()*2);
        player.setYRot(face.getOpposite().toYRot());
    }
    private static CinemaSeatTile install(GameTestHelper h,BlockPos pos,Direction facing) {
        h.getLevel().setBlockAndUpdate(pos,SeatsModule.CHAIR.get().defaultBlockState().setValue(CinemaSeatBlock.FACING,facing));
        return CinemaSeatBlock.findSeat(h.getLevel(),pos).orElseThrow();
    }
    private static boolean place(ServerPlayer player,BlockPos pos) {
        BlockPos floor=pos.below();
        BlockHitResult hit=new BlockHitResult(new Vec3(pos.getX()+.5,pos.getY(),pos.getZ()+.5),Direction.UP,floor,false);
        return ((BlockItem)SeatsModule.CHAIR_ITEM.get()).place(new BlockPlaceContext(new UseOnContext(player,InteractionHand.MAIN_HAND,hit))).consumesAction();
    }
    private static BlockHitResult click(ServerPlayer player,BlockPos pos) {
        Direction back=player.level().getBlockState(pos).getValue(CinemaSeatBlock.FACING).getOpposite();
        Vec3 target=pos.getCenter().add(back.getStepX()*.35,.375,back.getStepZ()*.35);
        return player.level().clip(new ClipContext(player.getEyePosition(),target,
            ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
    }
    private static int mounts(GameTestHelper h,BlockPos pos) {
        return h.getLevel().getEntitiesOfClass(CinemaSeatMount.class,new AABB(pos).inflate(3),m->!m.isRemoved()).size();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void physical_chair_real_item_shape_support_and_loot(GameTestHelper h) {
        BlockPos pos=area(h); ServerPlayer player=actor(h);
        BlockState[] supports={Blocks.STONE.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP)};
        for (BlockState support:supports) for (Direction face:Direction.Plane.HORIZONTAL) {
            h.getLevel().setBlockAndUpdate(pos.below(),support); near(player,pos,face);
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.CHAIR_ITEM.get(),2));
            h.assertTrue(place(player,pos),"real chair item accepts full top support in every facing");
            BlockState state=h.getLevel().getBlockState(pos);
            h.assertTrue(state.getValue(CinemaSeatBlock.FACING)==face && player.getMainHandItem().getCount()==1,"placement facing and survival consumption match actual item path");
            VoxelShape outline=state.getShape(h.getLevel(),pos),collision=state.getCollisionShape(h.getLevel(),pos);
            h.assertFalse(Shapes.joinIsNotEmpty(outline,collision,BooleanOp.NOT_SAME),"selection and collision are the identical multipart solid union");
            AABB bounds=outline.bounds();
            h.assertTrue(bounds.minX>=0 && bounds.maxX<=1 && bounds.minY==0 && bounds.maxY==1 && bounds.minZ>=0 && bounds.maxZ<=1,"all rendered/collidable chair parts stay in their single occupied cell");
            h.assertTrue(Block.getDrops(state,h.getLevel(),pos,h.getLevel().getBlockEntity(pos)).stream().filter(s->s.is(SeatsModule.CHAIR_ITEM.get())).mapToInt(ItemStack::getCount).sum()==1,"one chair drops exactly one ordinary chair item");
            h.getLevel().destroyBlock(pos,false);
        }
        for (BlockState unsupported:new BlockState[]{Blocks.STONE_SLAB.defaultBlockState(),Blocks.OAK_FENCE.defaultBlockState(),Blocks.AIR.defaultBlockState()}) {
            h.getLevel().setBlockAndUpdate(pos.below(),unsupported);
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.CHAIR_ITEM.get(),2));
            h.assertFalse(place(player,pos),"floating, lower-slab and fence-supported chairs are refused");
            h.assertTrue(player.getMainHandItem().getCount()==2,"rejected placement preserves item");
        }
        h.getLevel().setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        install(h,pos,Direction.NORTH);
        Vec3 start=new Vec3(pos.getX()+.25,pos.getY()+.125,pos.getZ()-1),end=start.add(0,0,3);
        h.assertTrue(h.getLevel().clip(new ClipContext(start,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player)).getType()==HitResult.Type.MISS,"empty space below the cushion is selectable floor space, never a bounding-box proxy");
        h.assertTrue(mounts(h,pos)==0,"empty physical chairs create no idle entities");
        h.getLevel().destroyBlock(pos.below(),false);
        h.assertTrue(h.getLevel().getBlockState(pos).isAir() && h.getLevel().getBlockEntity(pos)==null,"support loss removes chair and BE");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2)).stream().filter(e->e.getItem().is(SeatsModule.CHAIR_ITEM.get())).mapToInt(e->e.getItem().getCount()).sum()==1,"support loss drops one chair");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void physical_chair_identity_claim_and_native_chunk_roundtrip(GameTestHelper h) {
        BlockPos pos=area(h); CinemaSeatTile original=install(h,pos,Direction.WEST);
        UUID uid=original.seatUid(),controller=UUID.randomUUID();
        h.assertTrue(original.canBind(controller),"new physical seat is unclaimed");
        original.bindController(controller); original.bindController(controller);
        h.assertTrue(original.boundController().orElseThrow().equals(controller) && !original.canBind(UUID.randomUUID()),"one physical chair admits one controller, idempotently");
        original.unbindController(UUID.randomUUID());
        h.assertTrue(original.boundController().isPresent(),"unrelated controller cannot release claim");
        var world=h.getLevel(); CompoundTag chunk=ChunkSerializer.write(world,world.getChunkAt(pos));
        ProtoChunk restored=ChunkSerializer.read(world,world.getPoiManager(),new RegionStorageInfo("cinema-physical-seat-test",world.dimension(),"chunk"),new ChunkPos(pos),chunk);
        if (restored instanceof ImposterProtoChunk imposter) imposter.getWrapped().runPostLoad();
        CompoundTag saved=restored.getBlockEntityNbtForSaving(pos,world.registryAccess());
        h.assertTrue(saved!=null,"native chunk codec saves seat identity");
        CinemaSeatTile loaded=(CinemaSeatTile)BlockEntity.loadStatic(pos,restored.getBlockState(pos),saved,world.registryAccess());
        h.assertTrue(loaded!=null && loaded.seatUid().equals(uid) && loaded.boundController().orElseThrow().equals(controller),"native chunk reload preserves UID and controller claim");
        CinemaSeatTile movedClone=new CinemaSeatTile(pos.east(),original.getBlockState());
        movedClone.loadWithComponents(saved,world.registryAccess());
        h.assertTrue(!movedClone.seatUid().equals(uid) && movedClone.boundController().isEmpty(),"cloning to another position creates an unclaimed new physical identity");
        CompoundTag malformed=original.saveWithFullMetadata(world.registryAccess()); malformed.putString("seat_controller","broken-uuid");
        CinemaSeatTile invalid=new CinemaSeatTile(pos,original.getBlockState()); invalid.loadWithComponents(malformed,world.registryAccess());
        h.assertFalse(invalid.canBind(controller),"malformed controller claim fails closed");
        CinemaSeatTile invalidReload=new CinemaSeatTile(pos,original.getBlockState());
        invalidReload.loadWithComponents(invalid.saveWithFullMetadata(world.registryAccess()),world.registryAccess());
        h.assertFalse(invalidReload.canBind(controller),"malformed claim remains closed after another save/reload");
        world.destroyBlock(pos,false); CinemaSeatTile replacement=install(h,pos,Direction.WEST);
        h.assertFalse(CinemaSeatBlock.isValidSeat(world,pos,uid),"removed UID cannot bind its replacement");
        h.assertTrue(!replacement.seatUid().equals(uid) && replacement.boundController().isEmpty(),"ordinary replacement receives new unclaimed identity");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void physical_chair_occupancy_dismount_and_logout(GameTestHelper h) {
        BlockPos pos=area(h); CinemaSeatTile seat=install(h,pos,Direction.NORTH);
        ServerPlayer first=actor(h),second=actor(h); near(first,pos,Direction.NORTH); near(second,pos,Direction.NORTH);
        h.assertTrue(seat.sit(first,click(first,pos)),"block-driven server sit succeeds with real reach, support and headroom");
        CinemaSeatMount mount=(CinemaSeatMount)first.getVehicle();
        h.assertTrue(mount!=null && mounts(h,pos)==1 && seat.occupied(),"exactly one transient vehicle represents an occupied physical seat");
        h.assertFalse(seat.sit(second,click(second,pos)),"duplicate occupant is refused");
        h.assertFalse(mount.isPickable() || mount.canBeCollidedWith() || mount.isPushable() || mount.shouldBeSaved(),"vehicle supplies no selection, collision, persistence or push proxy");
        h.assertFalse(mount.save(new CompoundTag()) || mount.saveAsPassenger(new CompoundTag()),"entity persistence paths cannot resurrect empty seats or RootVehicle mounts");
        Vec3 expected=mount.getPassengerRidingPosition(first).subtract(first.getVehicleAttachmentPoint(mount));
        h.assertTrue(first.position().distanceToSqr(expected)<1e-12,"actual server passenger positioning uses vanilla vehicle attachment without a fabricated eye height");
        System.out.println("CINEMA_SEAT_ENGINE_POSE "+SeatRegistry.JSON.toJson(java.util.Map.of(
            "source","server_gametest", "facing","north", "mount_y_above_floor",mount.getY()-pos.getY(),
            "vehicle_attachment_y",first.getVehicleAttachmentPoint(mount).y,
            "player_feet_above_floor",first.getY()-pos.getY(), "eye_y_above_floor",first.getEyeY()-pos.getY(),
            "bounding_box_top_above_floor",first.getBoundingBox().maxY-pos.getY(), "pose",first.getPose().name())));
        first.stopRiding();
        h.assertTrue(!first.isPassenger() && CinemaSeatSafety.safeStanding(h.getLevel(),first,first.position()),"normal dismount reaches actual supported collision-free standing volume");
        h.assertTrue(BlockPos.containing(first.position()).equals(pos.north()),"front footwell is preferred when safe");
        mount.tick(); h.assertTrue(mount.isRemoved() && mounts(h,pos)==0,"dismounted chair leaves no idle vehicle");
        h.assertTrue(seat.sit(second,click(second,pos)),"chair becomes available for a new occupant");
        CinemaSeatMount secondMount=(CinemaSeatMount)second.getVehicle();
        CinemaSeatEvents.logout(new PlayerEvent.PlayerLoggedOutEvent(second));
        h.assertTrue(!second.isPassenger() && secondMount.isRemoved() && mounts(h,pos)==0,"logout cleanup ejects and removes the transient vehicle before save");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void physical_chair_blocked_exit_break_and_support_cleanup(GameTestHelper h) {
        BlockPos pos=area(h); CinemaSeatTile seat=install(h,pos,Direction.NORTH); ServerPlayer player=actor(h); near(player,pos,Direction.NORTH);
        h.assertTrue(seat.sit(player,click(player,pos)),"initial chair entry succeeds");
        CinemaSeatMount mount=(CinemaSeatMount)player.getVehicle();
        for (BlockPos wall:BlockPos.betweenClosed(pos.offset(-1,0,-1),pos.offset(1,2,1)))
            if (wall.getX()!=pos.getX() || wall.getZ()!=pos.getZ()) h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
        Vec3 before=player.position(); player.stopRiding();
        h.assertTrue(player.getVehicle()==mount && player.position().equals(before),"no-safe-exit dismount is canceled without teleporting through enclosing walls");
        h.getLevel().destroyBlock(pos,false);
        h.assertTrue(!player.isPassenger() && mount.isRemoved() && mounts(h,pos)==0,"breaking even a surrounded occupied chair removes the vehicle");
        h.assertTrue(CinemaSeatSafety.safeStanding(h.getLevel(),player,player.position()),"broken-chair cell itself becomes the safe exit when surrounding routes are blocked");
        for (BlockPos wall:BlockPos.betweenClosed(pos.offset(-1,0,-1),pos.offset(1,2,1))) h.getLevel().setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());
        CinemaSeatTile replacement=install(h,pos,Direction.NORTH); near(player,pos,Direction.NORTH);
        h.assertTrue(replacement.sit(player,click(player,pos)),"replaced chair remains ordinarily usable");
        CinemaSeatMount supportMount=(CinemaSeatMount)player.getVehicle(); h.getLevel().destroyBlock(pos.below(),false);
        h.assertTrue(!player.isPassenger() && supportMount.isRemoved() && h.getLevel().getBlockState(pos).isAir(),"support loss safely ejects and cleans chair, BE and mount");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void physical_chair_rejects_wall_reach_low_ceiling_and_neighbour_chair_exit(GameTestHelper h) {
        BlockPos pos=area(h); CinemaSeatTile seat=install(h,pos,Direction.NORTH); ServerPlayer player=actor(h); near(player,pos,Direction.NORTH);
        BlockHitResult forged=click(player,pos);
        h.getLevel().setBlockAndUpdate(pos.north().above(),Blocks.STONE.defaultBlockState());
        h.assertFalse(seat.sit(player,forged),"server raycast rejects a nominal chair hit through a wall");
        h.getLevel().setBlockAndUpdate(pos.north().above(),Blocks.AIR.defaultBlockState());
        h.getLevel().setBlockAndUpdate(pos.above(),Blocks.STONE.defaultBlockState());
        h.assertFalse(seat.sit(player,forged),"blocked seated/standing headroom prevents mount creation");
        CinemaSeatMount headroomProbe=new CinemaSeatMount(SeatsModule.CHAIR_MOUNT.get(),h.getLevel()); headroomProbe.initialize(seat,player);
        h.assertFalse(CinemaSeatSafety.canSit(h.getLevel(),player,headroomProbe),"actual body/headroom predicate independently rejects low ceiling");
        h.assertTrue(mounts(h,pos)==0,"failed requests leave no orphan vehicles");
        h.getLevel().setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        install(h,pos.north(),Direction.NORTH);
        // Approach from the side so the front chair does not intercept the server reach ray.
        near(player,pos,Direction.WEST);
        h.assertTrue(seat.sit(player,click(player,pos)),"chair can be reached from a clear side");
        CinemaSeatMount mount=(CinemaSeatMount)player.getVehicle();
        Vec3 exit=mount.safeExit(player).orElseThrow();
        h.assertFalse(BlockPos.containing(exit).equals(pos.north()),"neighboring chair is not an empty front footwell");
        player.stopRiding(); mount.tick();
        h.assertTrue(CinemaSeatSafety.safeStanding(h.getLevel(),player,player.position()),"alternative exit has support and headroom without another chair intersection");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void physical_chair_creative_survival_editing_and_reload_recovery(GameTestHelper h) {
        BlockPos pos=area(h); ServerPlayer player=actor(h);
        for (GameType mode:new GameType[]{GameType.CREATIVE,GameType.SURVIVAL}) {
            player.setGameMode(mode); near(player,pos,Direction.SOUTH);
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.CHAIR_ITEM.get(),2));
            h.assertTrue(place(player,pos),"actual item placement works in "+mode);
            h.assertTrue(player.getMainHandItem().getCount()==(mode==GameType.CREATIVE?2:1),"actual item count obeys creative/survival mode");
            CinemaSeatTile seat=CinemaSeatBlock.findSeat(h.getLevel(),pos).orElseThrow();
            h.assertTrue(seat.sit(player,click(player,pos)),"player can occupy a newly placed chair in "+mode);
            CinemaSeatMount mount=(CinemaSeatMount)player.getVehicle();
            UUID uid=seat.seatUid();
            h.assertTrue(player.gameMode.destroyBlock(pos),"player's normal break action works while occupying chair in "+mode);
            h.assertTrue(!player.isPassenger() && mount.isRemoved() && !CinemaSeatBlock.isValidSeat(h.getLevel(),pos,uid),"real break cleans occupancy and invalidates old identity");
        }
        player.setGameMode(GameType.SURVIVAL); near(player,pos,Direction.NORTH);
        CinemaSeatTile seat=install(h,pos,Direction.NORTH);
        h.assertTrue(seat.sit(player,click(player,pos)),"reload simulation begins with an occupied chair");
        CinemaSeatMount mount=(CinemaSeatMount)player.getVehicle();
        CompoundTag savedSession=player.getPersistentData().copy(); Vec3 savedOrigin=player.position();
        mount.releaseAndDiscard();
        // Simulate player NBT saved while mounted, followed by a fresh world with no saved vehicle.
        player.getPersistentData().merge(savedSession); player.setPos(savedOrigin);
        CinemaSeatEvents.login(new PlayerEvent.PlayerLoggedInEvent(player));
        h.assertTrue(!player.isPassenger() && mounts(h,pos)==0 && CinemaSeatSafety.safeStanding(h.getLevel(),player,player.position()),"reload recovery chooses a real local standing exit without resurrecting a mount");
        h.assertFalse(player.getPersistentData().contains("mycinema_seat_session"),"successful recovery clears transient player session metadata");
        h.assertTrue(CinemaSeatBlock.findSeat(h.getLevel(),pos).orElseThrow().seatUid().equals(seat.seatUid()),"player reload cleanup never replaces or recreates chair identity");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void physical_chair_four_facing_mount_exit_and_remote_rejection(GameTestHelper h) {
        BlockPos pos=area(h); ServerPlayer player=actor(h);
        for (Direction facing:Direction.Plane.HORIZONTAL) {
            CinemaSeatTile seat=install(h,pos,facing); near(player,pos,facing);
            BlockHitResult hit=click(player,pos);
            player.setPos(pos.getX()+30,pos.getY(),pos.getZ()+30);
            h.assertFalse(seat.sit(player,hit),"stale hit from beyond reach cannot mount "+facing);
            near(player,pos,facing);
            h.assertTrue(seat.sit(player,click(player,pos)),"real passenger can mount "+facing);
            CinemaSeatMount mount=(CinemaSeatMount)player.getVehicle();
            h.assertTrue(mount.position().distanceToSqr(CinemaSeatGeometry.mountPosition(pos,facing))<1e-12,"cardinal mount rotates with cushion and back");
            h.assertTrue(player.getYRot()==facing.toYRot(),"server authoritatively initializes viewer facing toward chair front");
            player.stopRiding();
            h.assertTrue(mount.isRemoved() && mounts(h,pos)==0,"dismount immediately removes empty vehicle without another tick");
            h.assertTrue(BlockPos.containing(player.position()).equals(pos.relative(facing)) && CinemaSeatSafety.safeStanding(h.getLevel(),player,player.position()),"safe front footwell follows facing "+facing);
            h.getLevel().destroyBlock(pos,false);
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void double_support_four_facings_identity_mount_exit_and_loot(GameTestHelper h) {
        BlockPos pos=area(h); ServerPlayer player=actor(h);
        for(Direction facing:Direction.Plane.HORIZONTAL){
            var block=SeatsModule.DOUBLE_SUPPORT_CHAIR.get();
            var state=block.defaultBlockState().setValue(CinemaSeatBlock.FACING,facing);
            h.getLevel().setBlockAndUpdate(pos,state);
            CinemaSeatTile seat=CinemaSeatBlock.findSeat(h.getLevel(),pos).orElseThrow();
            UUID uid=seat.seatUid(),controller=UUID.randomUUID();seat.bindController(controller);
            CompoundTag saved=seat.saveWithFullMetadata(h.getLevel().registryAccess());
            CinemaSeatTile restored=(CinemaSeatTile)BlockEntity.loadStatic(pos,state,saved,h.getLevel().registryAccess());
            h.assertTrue(restored!=null&&restored.seatUid().equals(uid)&&restored.boundController().orElseThrow().equals(controller),"same UID/controller round trip for variant");
            VoxelShape shape=state.getShape(h.getLevel(),pos);
            h.assertFalse(Shapes.joinIsNotEmpty(shape,state.getCollisionShape(h.getLevel(),pos),BooleanOp.NOT_SAME),"variant selection equals collision");
            AABB bounds=shape.bounds();
            h.assertTrue(bounds.minX>=0&&bounds.maxX<=1&&bounds.minY==0&&bounds.maxY<=1&&bounds.minZ>=0&&bounds.maxZ<=1,"variant owns exactly one cell");
            near(player,pos,facing);
            Vec3 target=block.mountPosition(pos,facing).add(0,-.005,0);
            BlockHitResult hit=h.getLevel().clip(new ClipContext(player.getEyePosition(),target,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
            h.assertTrue(seat.sit(player,hit),"double support real passenger entry "+facing);
            CinemaSeatMount mount=(CinemaSeatMount)player.getVehicle();
            h.assertTrue(mount.position().distanceToSqr(block.mountPosition(pos,facing))<1e-12,"variant uses its own cushion anchor");
            player.stopRiding();
            h.assertTrue(mount.isRemoved()&&CinemaSeatSafety.safeStanding(h.getLevel(),player,player.position()),"variant safe dismount and cleanup");
            h.assertTrue(Block.getDrops(state,h.getLevel(),pos,seat).stream().filter(stack->stack.is(SeatsModule.DOUBLE_SUPPORT_CHAIR_ITEM.get())).mapToInt(ItemStack::getCount).sum()==1,"variant drops one matching item");
            h.getLevel().destroyBlock(pos,false);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void double_support_real_item_and_support_loss(GameTestHelper h){
        BlockPos pos=area(h);ServerPlayer player=actor(h);near(player,pos,Direction.NORTH);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.DOUBLE_SUPPORT_CHAIR_ITEM.get(),2));
        var hit=new BlockHitResult(new Vec3(pos.getX()+.5,pos.getY(),pos.getZ()+.5),Direction.UP,pos.below(),false);
        h.assertTrue(((BlockItem)SeatsModule.DOUBLE_SUPPORT_CHAIR_ITEM.get()).place(new BlockPlaceContext(new UseOnContext(player,InteractionHand.MAIN_HAND,hit))).consumesAction(),"actual variant block item places");
        h.assertTrue(player.getMainHandItem().getCount()==1&&CinemaSeatBlock.findSeat(h.getLevel(),pos).isPresent(),"variant consumes one item and registers physical identity");
        h.getLevel().destroyBlock(pos.below(),false);
        h.assertTrue(h.getLevel().getBlockState(pos).isAir()&&h.getLevel().getBlockEntity(pos)==null,"support loss leaves no invisible variant");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2)).stream().filter(e->e.getItem().is(SeatsModule.DOUBLE_SUPPORT_CHAIR_ITEM.get())).mapToInt(e->e.getItem().getCount()).sum()==1,"support loss returns exactly one matching item");
        h.succeed();
    }

}
