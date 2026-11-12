package local.cinemamanager.seats.fractional;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Native source regressions; execution remains a separate acceptance gate. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FractionalSeatContactGameTests {
    @GameTest(template="empty",timeoutTicks=60)
    public static void selected_contact_full_floor_and_shared_slab_are_owner_scoped(GameTestHelper h){
        var level=h.getLevel();BlockPos p=h.absolutePos(new BlockPos(5,3,5));
        for(BlockPos q:BlockPos.betweenClosed(p.offset(-2,-1,-2),p.offset(2,3,2)))level.setBlockAndUpdate(q,Blocks.AIR.defaultBlockState());
        ServerPlayer rider=new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),"ContactQA"),ClientInformation.createDefault());
        Vec3 floor=new Vec3(p.getX()+.5,p.getY(),p.getZ()+.5);UUID uid=UUID.randomUUID();var anchor=new FractionalSeatData.Anchor(floor.x,floor.y,floor.z,0);int voxel=(32*64+40)*64+32;
        var own=new FractionalSeatData.Contribution(uid,anchor,new int[]{voxel,voxel+1},FractionalSeatGeometryProfile.HIGHBACK_150);
        var footprint=new FractionalSeatFootprint(uid,Map.of(p,own));
        var seat=new FractionalSeatLayout.Seat(uid,"QA",anchor,floor.add(0,.455,0),floor.add(0,.5,0),floor,floor.add(0,0,-1),footprint);
        ArmorStand marker=marker(level,seat.marker());Vec3 feet=marker.getPassengerRidingPosition(rider).subtract(rider.getVehicleAttachmentPoint(marker));AABB body=rider.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(feet);
        level.setBlockAndUpdate(p.below(),Blocks.GRAY_CONCRETE.defaultBlockState());FractionalSeatCells.installWithVerifiedSlabHost(level,footprint);
        var initial=FractionalSeatSupportContact.evaluate(level,rider,marker,seat,body,feet,"TEST");
        h.assertTrue(initial.allowed(),"exact gray-concrete support accepts calibrated contact: reason="+initial.check().reason()+" detail="+initial.check().detail()+" body="+body+" feet="+feet+" floor="+floor);
        BlockPos peerOnly=p.above();
        level.setBlockAndUpdate(peerOnly,FractionalSeatModule.CELL.get().defaultBlockState());
        var emptyPeer=FractionalSeatExitSafety.assessEntry(level,rider,marker,seat).firstFailure();
        h.assertTrue(emptyPeer.reason()==FractionalSeatExitSafety.Reason.INVALID_CELL && peerOnly.equals(emptyPeer.block()),"live empty peer-only transport cannot hide geometry");
        level.removeBlockEntity(peerOnly);
        var missingPeer=FractionalSeatExitSafety.assessEntry(level,rider,marker,seat).firstFailure();
        h.assertTrue(missingPeer.reason()==FractionalSeatExitSafety.Reason.INVALID_CELL && peerOnly.equals(missingPeer.block()),"missing or automatically recreated empty peer-only tile outside own footprint cannot hide geometry");
        level.setBlockAndUpdate(peerOnly,Blocks.AIR.defaultBlockState());FractionalSeatCells.remove(level,footprint);
        for(var wrong:List.of(Blocks.STONE.defaultBlockState(),Blocks.REDSTONE_LAMP.defaultBlockState(),Blocks.MAGMA_BLOCK.defaultBlockState(),Blocks.LAVA.defaultBlockState(),Blocks.AIR.defaultBlockState(),Blocks.STONE_STAIRS.defaultBlockState())){
            level.setBlockAndUpdate(p.below(),wrong);h.assertFalse(FractionalSeatSupportContact.evaluate(level,rider,marker,seat,body,feet,"TEST").allowed(),"foreign/hazardous/missing/sloped support refused");}
        level.setBlockAndUpdate(p.below(),FractionalSeatHost.ANDESITE_BOTTOM_SLAB);floor=floor.add(0,-.5,0);anchor=new FractionalSeatData.Anchor(floor.x,floor.y,floor.z,0);
        own=new FractionalSeatData.Contribution(uid,anchor,new int[]{voxel,voxel+1},FractionalSeatGeometryProfile.HIGHBACK_150);footprint=new FractionalSeatFootprint(uid,Map.of(p.below(),own));
        seat=new FractionalSeatLayout.Seat(uid,"QA",anchor,floor.add(0,.455,0),floor.add(0,.5,0),floor,floor.add(0,0,-1),footprint);marker=marker(level,seat.marker());
        feet=marker.getPassengerRidingPosition(rider).subtract(rider.getVehicleAttachmentPoint(marker));body=rider.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(feet);
        h.assertFalse(FractionalSeatSupportContact.evaluate(level,rider,marker,seat,body,feet,"TEST").allowed(),"plain slab without exact own tile contribution refused");
        level.setBlockAndUpdate(p.below(),FractionalSeatHost.ANDESITE_BOTTOM_SLAB.setValue(SlabBlock.WATERLOGGED,true));
        h.assertFalse(FractionalSeatSupportContact.evaluate(level,rider,marker,seat,body,feet,"TEST").allowed(),"wet slab refused");
        level.setBlockAndUpdate(p.below(),FractionalSeatHost.ANDESITE_BOTTOM_SLAB.setValue(SlabBlock.TYPE,SlabType.TOP));
        h.assertFalse(FractionalSeatSupportContact.evaluate(level,rider,marker,seat,body,feet,"TEST").allowed(),"top slab refused");
        level.setBlockAndUpdate(p.below(),FractionalSeatHost.ANDESITE_BOTTOM_SLAB);FractionalSeatCells.installWithVerifiedSlabHost(level,footprint);
        var contact=FractionalSeatSupportContact.evaluate(level,rider,marker,seat,body,feet,"TEST");h.assertTrue(contact.allowed(),"valid retained host plus exact own UID accepts contact");
        UUID peer=UUID.randomUUID();var other=new FractionalSeatData.Contribution(peer,anchor,new int[]{voxel+2,voxel+3},FractionalSeatGeometryProfile.HIGHBACK_150);var peerFootprint=new FractionalSeatFootprint(peer,Map.of(p.below(),other));
        FractionalSeatCells.installWithVerifiedSlabHost(level,peerFootprint);h.assertFalse(contact.stillValid(level),"owner change invalidates old proof");
        h.assertTrue(FractionalSeatSupportContact.ownerBlocks(other,body.deflate(1e-7),p.below()),"peer solid tested against full body");
        var shape=FractionalSeatTile.buildShape(FractionalSeatData.of(List.of(other)),Blocks.AIR.defaultBlockState()).move(p.getX(),p.getY()-1,p.getZ());
        h.assertTrue(Shapes.joinIsNotEmpty(Shapes.create(body.deflate(1e-7)),shape,BooleanOp.AND)==FractionalSeatSupportContact.ownerBlocks(other,body.deflate(1e-7),p.below()),"RLE agrees with native shape");
        FractionalSeatCells.remove(level,peerFootprint);FractionalSeatCells.remove(level,footprint);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void selected_contact_proves_whole_coverage_and_retains_peer_slice_collision(GameTestHelper h){
        VoxelShape body=Shapes.box(.2,-.145,.2,.8,1.655,.8),slice=Shapes.box(.2,-.145,.2,.8,0,.8),host=Shapes.box(0,-1,0,1,0,1);
        VoxelShape gap=Shapes.or(Shapes.box(0,-1,0,.49,0,1),Shapes.box(.51,-1,0,1,0,1));
        h.assertTrue(FractionalSeatSupportContact.fullyCovered(slice,host),"full contact coverage");h.assertFalse(FractionalSeatSupportContact.fullyCovered(slice,gap),"central hole fails despite four supported corners");
        h.assertFalse(FractionalSeatSupportContact.hostBlocks(body,host,slice),"verified host sliver only");
        int at=(32*64+60)*64+32;var peer=new FractionalSeatData.Contribution(UUID.randomUUID(),new FractionalSeatData.Anchor(.5,-1,.5,0),new int[]{at,at+1},FractionalSeatGeometryProfile.HIGHBACK_150);
        h.assertTrue(FractionalSeatSupportContact.ownerBlocks(peer,new AABB(.2,-.145,.2,.8,1.655,.8),new BlockPos(0,-1,0)),"peer in host contact slice remains collidable");h.succeed();
    }
    private static ArmorStand marker(net.minecraft.server.level.ServerLevel level,Vec3 at){ArmorStand m=new ArmorStand(EntityType.ARMOR_STAND,level);m.setPos(at);m.setNoGravity(true);m.setInvisible(true);CompoundTag t=new CompoundTag();m.saveWithoutId(t);t.putBoolean("Marker",true);m.load(t);return m;}
    private FractionalSeatContactGameTests(){}
}
