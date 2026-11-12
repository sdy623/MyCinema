package local.cinemamanager.qa;

import local.cinemamanager.*;
import local.cinemamanager.panels.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** Opt-in independent-world fixture: five distinct owned panels, no invented film or media grants. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class PosterGalleryQa {
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        if(!Boolean.getBoolean("mycinema.qa"))return;
        event.getDispatcher().register(Commands.literal("mycinemapostergallery").requires(s->s.hasPermission(2))
                .executes(c->{create(c.getSource().getPlayerOrException());return 1;}));
    }
    private static void create(ServerPlayer actor){
        if(!actor.getServer().getWorldData().getLevelName().toLowerCase(Locale.ROOT).contains("qa"))throw new IllegalStateException("Independent QA world required");
        var level=actor.serverLevel();BlockPos controller=new BlockPos(-10,-60,13);
        if(!level.hasChunkAt(controller)||!(level.getBlockEntity(controller) instanceof ManagerTile manager)
                ||!actor.getUUID().equals(manager.owner)||manager.state.halls.size()!=1)throw new IllegalStateException("Exact owned one-hall QA manager required");
        List<BlockPos> targets=List.of(new BlockPos(-17,-59,40),new BlockPos(-14,-59,40),new BlockPos(-11,-59,40),new BlockPos(-8,-59,40),new BlockPos(-5,-59,40));
        for(var pos:targets)if(!level.hasChunkAt(pos)||!level.getBlockState(pos).isAir())throw new IllegalStateException("Poster fixture refuses occupied or unloaded destination");
        actor.teleportTo(-10.5,-60,36.5);actor.setYRot(0);actor.setXRot(0);
        var settings=new PanelSettings(PanelSettings.Layout.POSTER_TOP,"","","","","","",0xe6f5ff,0x101b28,0x66eeee,100,PanelSettings.PUBLIC);
        for(var pos:targets){
            level.setBlock(pos,CinemaManager.INFO.get().defaultBlockState().setValue(InfoBlock.FACING,Direction.NORTH),3);
            var panel=(InfoTile)level.getBlockEntity(pos);panel.initializePlacement(actor.getUUID());
            panel.configure(actor,PanelGroup.of(panel).signature(),settings,PanelScope.HALL);
            panel.bind(actor,manager,manager.state.halls.getFirst().id);panel.refreshNow();
        }
        actor.sendSystemMessage(Component.literal("Five independent owned information panels are bound to the existing hall. No film, source, poster, consent or playback was created. Load the reviewed film through the normal library UI."));
    }
}
