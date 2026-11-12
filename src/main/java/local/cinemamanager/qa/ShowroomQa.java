package local.cinemamanager.qa;

import local.cinemamanager.*;
import local.cinemamanager.access.*;
import local.cinemamanager.advertising.*;
import local.cinemamanager.doors.*;
import local.cinemamanager.panels.PanelScope;
import local.cinemamanager.seats.*;
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
import java.util.*;

/** Explicit opt-in synthetic fixture. Refuses occupied targets and never touches the existing cinema. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class ShowroomQa {
    private ShowroomQa(){}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        if(!Boolean.getBoolean("mycinema.qa"))return;
        event.getDispatcher().register(Commands.literal("mycinemashowroom").requires(s->s.hasPermission(2))
                .then(Commands.literal("setup").executes(c->{setup(c.getSource().getPlayerOrException());return 1;})));
    }
    private static void setup(ServerPlayer player){
        if(!player.server.getWorldData().getLevelName().toLowerCase(Locale.ROOT).contains("qa"))throw new IllegalStateException("Independent QA worlds only");
        var level=player.serverLevel();Map<BlockPos,BlockState> targets=new LinkedHashMap<>();
        for(int x=-15;x<=3;x++)for(int z=26;z<=35;z++)targets.put(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
        for(int x:new int[]{-14,-13,-6,-5,-1,2})for(int y=-60;y<=-58;y++)targets.put(new BlockPos(x,y,34),Blocks.GRAY_CONCRETE.defaultBlockState());
        var led=CinemaManager.AD_LED.get().defaultBlockState().setValue(AdvertisingBlock.FACING,Direction.NORTH);
        for(BlockPos pos:List.of(new BlockPos(-13,-60,33),new BlockPos(-14,-60,33),new BlockPos(-13,-59,33),new BlockPos(-14,-59,33),new BlockPos(-5,-60,33),new BlockPos(-1,-60,33)))targets.put(pos,led);
        targets.put(new BlockPos(-9,-60,33),CinemaManager.AD_PRINTED.get().defaultBlockState().setValue(AdvertisingBlock.FACING,Direction.NORTH).setValue(AdvertisingBlock.FLOOR,true));
        targets.put(new BlockPos(-10,-60,28),AccessModule.READER.get().defaultBlockState());
        var door=CinemaManager.DOOR_METAL.get().defaultBlockState().setValue(CinemaDoorBlock.FACING,Direction.WEST);
        for(DoorPart part:DoorPart.values())targets.put(new BlockPos(-7,-60+part.offset(),28),door.setValue(CinemaDoorBlock.PART,part));
        for(int x:new int[]{-3,-1,1})targets.put(new BlockPos(x,-60,28),Blocks.OAK_STAIRS.defaultBlockState());
        targets.put(new BlockPos(2,-59,33),SeatsModule.GUIDE.get().defaultBlockState().setValue(SeatGuideBlock.FACING,Direction.NORTH));
        targets.put(new BlockPos(3,-60,30),Blocks.CHEST.defaultBlockState());
        for(var entry:targets.entrySet()){
            BlockPos pos=entry.getKey();
            if(!level.hasChunkAt(pos)||level.getBlockEntity(pos)!=null)throw new IllegalStateException("Fixture refuses unloaded/occupied entity at "+pos);
            BlockState old=level.getBlockState(pos);
            if(pos.getY()==-61){if(!old.isAir()&&!old.is(Blocks.GRASS_BLOCK)&&!old.is(Blocks.DIRT)&&!old.is(Blocks.SMOOTH_STONE))throw new IllegalStateException("Unexpected floor at "+pos);}
            else if(!old.isAir())throw new IllegalStateException("Fixture refuses occupied block at "+pos);
        }
        // Supports first, then parts without intermediate neighbor updates, then one coherent publication.
        for(var entry:targets.entrySet())level.setBlock(entry.getKey(),entry.getValue(),18);
        for(BlockPos pos:targets.keySet()){
            var tile=level.getBlockEntity(pos);
            if(tile instanceof InfoTile info){info.initializePlacement(player.getUUID());info.scope=PanelScope.MANUAL;info.dirty();}
            else if(tile instanceof StaffReaderTile reader)reader.initialize(player.getUUID());
            else if(tile instanceof SeatGuideTile guide)guide.initialize(player.getUUID(),ItemStack.EMPTY);
        }
        var chest=(ChestBlockEntity)level.getBlockEntity(new BlockPos(3,-60,30));
        var stacks=List.of(new ItemStack(CinemaManager.DISPLAY_LINKER.get()),new ItemStack(CinemaManager.AD_LED_ITEM.get(),12),new ItemStack(CinemaManager.AD_PRINTED_ITEM.get(),3),new ItemStack(AccessModule.PAIRING_CARD.get()),new ItemStack(AccessModule.CARD.get()),new ItemStack(AccessModule.READER_ITEM.get(),2),new ItemStack(SeatsModule.BINDER.get()),new ItemStack(SeatsModule.GUIDE_ITEM.get(),16),new ItemStack(CinemaManager.MANUAL.get()),new ItemStack(CinemaManager.LINKER.get()));
        for(int i=0;i<stacks.size();i++)chest.setItem(i,stacks.get(i));chest.setChanged();
        for(BlockPos pos:targets.keySet()){level.updateNeighborsAt(pos,level.getBlockState(pos).getBlock());level.sendBlockUpdated(pos,level.getBlockState(pos),level.getBlockState(pos),3);}
        player.sendSystemMessage(Component.literal("Showroom QA fixtures ready: matching 2x2 LED units x=-14..-13/z33; printed stand x=-9; video LED x=-5; information LED x=-1; reader(-10,-60,28), door(-7,-60,28), seats x=-3/-1/1,z28; guide(2,-59,33); tools chest(3,-60,30). No mode, card permission, source binding or seat record has been preconfigured."));
    }
}
