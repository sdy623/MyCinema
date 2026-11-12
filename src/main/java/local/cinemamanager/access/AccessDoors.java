package local.cinemamanager.access;

import java.util.*;
import local.cinemamanager.doors.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Explicit managed-door bridge. Unbound map/datapack doors retain their original behavior. */
public final class AccessDoors {
    private static List<BlockPos> leaves(ServerLevel level,BlockPos base){
        if(!level.hasChunkAt(base)||!(level.getBlockState(base).getBlock() instanceof CinemaDoorBlock door)||!door.isComplete(level,base))return List.of();
        BlockState state=level.getBlockState(base);
        BlockPos other=base.relative(DoorGeometry.partnerDirection(state.getValue(CinemaDoorBlock.FACING),state.getValue(CinemaDoorBlock.HINGE)));
        if(!level.hasChunkAt(other))return List.of();
        BlockState partner=level.getBlockState(other);
        List<BlockPos> result=new ArrayList<>();result.add(base.immutable());
        if(partner.is(door)&&partner.getValue(CinemaDoorBlock.PART)==DoorPart.LOWER
                &&DoorGeometry.compatible(state.getValue(CinemaDoorBlock.FACING),state.getValue(CinemaDoorBlock.HINGE),partner.getValue(CinemaDoorBlock.FACING),partner.getValue(CinemaDoorBlock.HINGE))){
            if(!door.isComplete(level,other))return List.of();result.add(other.immutable());
        }
        result.sort(Comparator.comparingLong(BlockPos::asLong));return List.copyOf(result);
    }
    private static String shape(ServerLevel level,BlockPos base){
        BlockState s=level.getBlockState(base);StringBuilder value=new StringBuilder(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()).append('|').append(s.getValue(CinemaDoorBlock.FACING).getSerializedName()).append('|').append(s.getValue(CinemaDoorBlock.HINGE).getSerializedName());
        for(DoorPart part:DoorPart.values())value.append('|').append(level.getBlockState(base.above(part.offset())).getValue(CinemaDoorBlock.ACCESS).getSerializedName());
        return value.toString();
    }
    public static boolean current(ServerLevel level,StaffAccessRegistry.Door binding){
        if(binding==null)return false;
        if(FixedIronDoorAccess.candidate(level,BlockPos.of(binding.base())))return FixedIronDoorAccess.current(level,binding);
        List<BlockPos> actual=leaves(level,BlockPos.of(binding.base()));
        if(actual.size()!=binding.leaves.size())return false;
        for(int i=0;i<actual.size();i++)if(actual.get(i).asLong()!=binding.leaves.get(i).pos()||!shape(level,actual.get(i)).equals(binding.leaves.get(i).shape())||StaffAccessRegistry.get(level).door(actual.get(i).asLong())!=binding)return false;
        return true;
    }
    public static void bind(ServerPlayer actor,StaffReaderTile reader,BlockPos clicked){
        reader.requireEditor(actor);ServerLevel level=actor.serverLevel();
        if(FixedStaffDoorPlan.profile(level)&&FixedStaffDoorPlan.at(clicked)!=null&&StaffMigrationRegistryGuard.fault(level)!=null)throw new IllegalArgumentException("mycinema.access.stale");
        if(FixedIronDoorAccess.candidate(level,clicked)){FixedIronDoorAccess.bind(actor,reader,clicked);return;}
        if(!level.hasChunkAt(clicked)||!(level.getBlockState(clicked).getBlock() instanceof CinemaDoorBlock door))throw new IllegalArgumentException("mycinema.access.invalid");
        BlockPos base=CinemaDoorBlock.lowerPos(clicked,level.getBlockState(clicked));List<BlockPos> leaves=leaves(level,base);
        if(leaves.isEmpty()||actor.distanceToSqr(Vec3.atCenterOf(base))>64||reader.getBlockPos().distSqr(base)>16*16)throw new IllegalArgumentException("mycinema.access.range");
        StaffAccessRegistry registry=StaffAccessRegistry.get(level);
        for(BlockPos leaf:leaves){
            if(!level.mayInteract(actor,leaf))throw new IllegalArgumentException("mycinema.access.permission");
            var old=registry.door(leaf.asLong());
            if(old!=null&&!old.reader.equals(reader.identity)&&!actor.hasPermissions(2)){
                BlockPos oldPos=BlockPos.of(old.readerPos);
                if(!level.hasChunkAt(oldPos)||!(level.getBlockEntity(oldPos) instanceof StaffReaderTile previous)||!previous.identity.equals(old.reader)||!previous.permits(actor,Double.POSITIVE_INFINITY))throw new IllegalArgumentException("mycinema.access.claimed");
            }
        }
        var facing=level.getBlockState(base).getValue(CinemaDoorBlock.FACING);
        boolean frontInside=DoorAccess.STAFF_FRONT.allows(facing,base,actor.position());
        boolean backInside=DoorAccess.STAFF_BACK.allows(facing,base,actor.position());
        if(frontInside==backInside)throw new IllegalArgumentException("mycinema.access.side");
        DoorAccess mode=frontInside?DoorAccess.STAFF_BACK:DoorAccess.STAFF_FRONT;
        // Reserve the server token before touching block states; capacity denial cannot half-pair a door.
        List<StaffAccessRegistry.Leaf> expected=new ArrayList<>();
        for(BlockPos leaf:leaves){BlockState s=level.getBlockState(leaf);expected.add(new StaffAccessRegistry.Leaf(leaf.asLong(),BuiltInRegistries.BLOCK.getKey(s.getBlock())+"|"+s.getValue(CinemaDoorBlock.FACING).getSerializedName()+"|"+s.getValue(CinemaDoorBlock.HINGE).getSerializedName()+"|"+mode.getSerializedName()+"|"+mode.getSerializedName()+"|"+mode.getSerializedName()));}
        var binding=new StaffAccessRegistry.Door(UUID.randomUUID(),reader.identity,reader.getBlockPos().asLong(),expected,reader.controllerId,reader.controllerOwner,reader.controllerPos.asLong());registry.bind(binding);
        for(BlockPos leaf:leaves)for(DoorPart part:DoorPart.values()){BlockPos pos=leaf.above(part.offset());level.setBlock(pos,level.getBlockState(pos).setValue(CinemaDoorBlock.ACCESS,mode),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);}
        reader.invalidatePairing();reader.doorPos=BlockPos.of(binding.base());reader.doorId=binding.token;reader.dirty();
        for(BlockPos leaf:leaves)level.scheduleTick(leaf,door,1);
    }
    private static boolean insideLeaf(Player actor,ServerLevel level,BlockPos base,StaffAccessRegistry.Door binding){
        if(!level.hasChunkAt(base)||!(level.getBlockState(base).getBlock() instanceof CinemaDoorBlock door)||!door.isComplete(level,base))return false;
        BlockState state=level.getBlockState(base);Direction facing=state.getValue(CinemaDoorBlock.FACING);
        for(DoorPart part:DoorPart.values())if(!level.getBlockState(base.above(part.offset())).getValue(CinemaDoorBlock.ACCESS).allows(facing,base,actor.position()))return false;
        if(binding==null)return true;
        var captured=binding.leaves.stream().filter(l->l.pos()==base.asLong()).findFirst().orElse(null);if(captured==null)return false;
        String[] original=captured.shape().split("\\|");
        if(original.length!=6||!original[0].equals(BuiltInRegistries.BLOCK.getKey(door).toString())||!original[1].equals(facing.getSerializedName())||!original[2].equals(state.getValue(CinemaDoorBlock.HINGE).getSerializedName()))return false;
        try{for(int i=3;i<6;i++)if(!DoorAccess.valueOf(original[i].toUpperCase(Locale.ROOT)).allows(facing,base,actor.position()))return false;}catch(RuntimeException invalid){return false;}
        return true;
    }
    /** Captured and current side checks protect entry even when the reciprocal chunk is unloaded. */
    public static boolean egress(Player player,Level level,BlockPos base){
        if(!(level instanceof ServerLevel server)||player==null||player.level()!=level||player.isSpectator()||!level.mayInteract(player,base))return false;
        if(FixedIronDoorAccess.candidate(level,base))return FixedIronDoorAccess.egress(player,server,base);
        var binding=StaffAccessRegistry.get(server).door(base.asLong());
        if(binding==null||!insideLeaf(player,server,base,binding))return false;
        BlockState state=level.getBlockState(base);BlockPos other=base.relative(DoorGeometry.partnerDirection(state.getValue(CinemaDoorBlock.FACING),state.getValue(CinemaDoorBlock.HINGE)));
        if(level.hasChunkAt(other)){
            BlockState s=level.getBlockState(other);var door=(CinemaDoorBlock)state.getBlock();
            if(s.is(door)&&s.getValue(CinemaDoorBlock.PART)==DoorPart.LOWER&&DoorGeometry.compatible(state.getValue(CinemaDoorBlock.FACING),state.getValue(CinemaDoorBlock.HINGE),s.getValue(CinemaDoorBlock.FACING),s.getValue(CinemaDoorBlock.HINGE))&&door.isComplete(level,other)
                    &&!insideLeaf(player,server,other,StaffAccessRegistry.get(server).door(other.asLong())))return false;
        }
        return pulse(server,binding,base);
    }
    public static boolean pulse(ServerLevel level,StaffAccessRegistry.Door binding,BlockPos base){
        if(FixedIronDoorAccess.candidate(level,base))return FixedIronDoorAccess.pulse(level,binding,base);
        if(!level.hasChunkAt(base)||!(level.getBlockState(base).getBlock() instanceof CinemaDoorBlock door)||!door.isComplete(level,base))return false;
        StaffAccessRegistry.get(level).pulse(binding,level.getGameTime());
        boolean opened=door.setOpen(null,level,base,true);if(opened)level.scheduleTick(base,door,1);return opened;
    }
    /** Redstone wins; expired managed openings close even if the reader/controller was removed. */
    public static boolean reconciledOpen(Level level,BlockPos base,BlockPos other,boolean powered,boolean vanilla){
        if(!(level instanceof ServerLevel server))return vanilla;var registry=StaffAccessRegistry.get(server);var a=registry.door(base.asLong());var b=other==null?null:registry.door(other.asLong());
        if(a==null&&b==null)return vanilla;return powered||(a!=null&&a.held(level.getGameTime()))||(b!=null&&b.held(level.getGameTime()))||FixedStaffDoorLifecycle.occupiedOpen(level,base)||(other!=null&&FixedStaffDoorLifecycle.occupiedOpen(level,other));
    }
    public static void removed(Level level,BlockPos base){if(level instanceof ServerLevel server)StaffAccessRegistry.get(server).invalidate(base.asLong());}
    private AccessDoors(){}
}
