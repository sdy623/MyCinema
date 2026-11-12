package local.cinemamanager.access;

import java.util.*;
import local.cinemamanager.*;
import local.cinemamanager.doors.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Independent native QA construction only: no pairing, employee grant or tablet activation. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class TabletAccessQa {
    private static final BlockPos MANAGER=new BlockPos(45,-60,20),CHEST=new BlockPos(40,-60,24);
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
    private TabletAccessQa(){}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        if(!Boolean.getBoolean("mycinema.qa"))return;
        event.getDispatcher().register(Commands.literal("mycinematabletqa").requires(source->source.hasPermission(2))
                .then(Commands.literal("setup").executes(context->{setup(context.getSource().getPlayerOrException());return 1;})));
    }
    private static void setup(ServerPlayer actor){
        if(!Boolean.getBoolean("mycinema.qa")||!actor.hasPermissions(2)||!StaffReaderTile.real(actor)
                ||!actor.server.getWorldData().getLevelName().toLowerCase(Locale.ROOT).contains("qa"))throw new IllegalStateException("Independent QA worlds and an authorized real operator only");
        var level=actor.serverLevel();Map<BlockPos,BlockState> targets=new LinkedHashMap<>(),before=new LinkedHashMap<>();
        for(int x=40;x<=55;x++)for(int z=26;z<=30;z++)targets.put(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
        for(int z=20;z<=26;z++)targets.put(new BlockPos(45,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
        targets.put(CHEST.below(),Blocks.SMOOTH_STONE.defaultBlockState());
        targets.put(MANAGER,CinemaManager.MANAGER.get().defaultBlockState());
        targets.put(CHEST,Blocks.CHEST.defaultBlockState());
        var door=CinemaManager.DOOR_METAL.get().defaultBlockState().setValue(CinemaDoorBlock.FACING,Direction.NORTH);
        for(int x:new int[]{43,48,53}){
            targets.put(new BlockPos(x-2,-60,28),AccessModule.READER.get().defaultBlockState());
            for(DoorPart part:DoorPart.values())targets.put(new BlockPos(x,-60+part.offset(),28),door.setValue(CinemaDoorBlock.PART,part));
        }
        for(var entry:targets.entrySet()){
            BlockPos pos=entry.getKey();
            if(!level.hasChunkAt(pos)||!level.mayInteract(actor,pos)||level.getBlockEntity(pos)!=null)
                throw new IllegalStateException("Fixture refuses unloaded, protected or occupied target at "+pos);
            BlockState old=level.getBlockState(pos);
            if(pos.getY()==-61){
                if(!old.isAir()&&!old.is(Blocks.GRASS_BLOCK)&&!old.is(Blocks.DIRT)&&!old.is(Blocks.STONE)&&!old.is(Blocks.SMOOTH_STONE))
                    throw new IllegalStateException("Fixture refuses unexpected floor at "+pos);
            }else if(!old.isAir())throw new IllegalStateException("Fixture refuses occupied block at "+pos);
            if(StaffAccessRegistry.get(level).door(pos.asLong())!=null)throw new IllegalStateException("Fixture refuses an existing door registration at "+pos);
            before.put(pos,old);
        }
        List<BlockPos> written=new ArrayList<>();
        try{
            for(var entry:targets.entrySet()){
                BlockPos pos=entry.getKey();if(!level.getBlockState(pos).equals(before.get(pos)))throw new IllegalStateException("Target changed before construction at "+pos);
                if(entry.getValue().equals(before.get(pos)))continue;
                written.add(pos);level.setBlock(pos,entry.getValue(),FLAGS);
                if(!level.getBlockState(pos).equals(entry.getValue()))throw new IllegalStateException("Fixture placement failed at "+pos);
            }
            if(!(level.getBlockEntity(MANAGER) instanceof ManagerTile manager))throw new IllegalStateException("QA manager did not initialize");
            manager.initializePlacement(actor.getUUID());manager.state.name="Tablet QA Cinema B";manager.dirty();
            for(int x:new int[]{41,46,51}){
                if(!(level.getBlockEntity(new BlockPos(x,-60,28)) instanceof StaffReaderTile reader))throw new IllegalStateException("QA reader did not initialize");
                reader.initialize(actor.getUUID());
            }
            if(!(level.getBlockEntity(CHEST) instanceof ChestBlockEntity chest))throw new IllegalStateException("QA tools chest did not initialize");
            List<ItemStack> tools=List.of(new ItemStack(AccessModule.PAIRING_CARD.get()),new ItemStack(CinemaManager.TABLET.get()),new ItemStack(AccessModule.CARD.get()),new ItemStack(AccessModule.CARD.get()));
            for(int i=0;i<tools.size();i++)chest.setItem(i,tools.get(i));chest.setChanged();
            for(BlockPos pos:written){level.updateNeighborsAt(pos,level.getBlockState(pos).getBlock());level.sendBlockUpdated(pos,level.getBlockState(pos),level.getBlockState(pos),Block.UPDATE_CLIENTS);}
        }catch(RuntimeException failure){
            int unrestored=0;Collections.reverse(written);
            for(BlockPos pos:written){
                try{
                    BlockState current=level.getBlockState(pos);if(current.equals(before.get(pos)))continue;
                    if(!current.is(targets.get(pos).getBlock())){unrestored++;continue;}
                    if(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)chest.clearContent();
                    level.setBlock(pos,before.get(pos),FLAGS);if(!level.getBlockState(pos).equals(before.get(pos)))unrestored++;
                }catch(RuntimeException rollbackFailure){unrestored++;}
            }
            actor.sendSystemMessage(Component.literal("Tablet QA construction failed; own writes rolled back"+(unrestored==0?".":" except "+unrestored+" changed targets; inspect before retrying.")));
            throw failure;
        }
        actor.sendSystemMessage(Component.literal("Tablet QA fixtures ready, all unpaired: readers (41,-60,28), (46,-60,28), (51,-60,28); corresponding doors x=43/48/53 at y=-60,z=28. Cinema B terminal (45,-60,20), blank tools chest (40,-60,24). Pair first reader to existing cinema A (-10,-60,13), second to B, and leave third unpaired until after tablet activation. No access grants or activations were created."));
    }
}
