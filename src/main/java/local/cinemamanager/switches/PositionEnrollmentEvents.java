package local.cinemamanager.switches;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import local.cinemamanager.CinemaManager;
import static local.cinemamanager.switches.RegisteredSwitchEngine.*;

/** Conservative invalidation on these events only; offline edits and event-less setBlock are not observed. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class PositionEnrollmentEvents {
    private static void at(ServerLevel level,BlockPos pos,String reason){
        if(level!=level.getServer().overworld())return;
        PositionEnrollment r=PositionEnrollment.read(level);
        FloorEnrollmentEvents.at(level,pos,reason);
        IeInstallationEvents.at(level,pos,reason);if(r==null)return;
        for(Device d:RegisteredSwitchRegistry.DEVICES)if(d.kind()==Kind.LEVER&&pos.equals(new BlockPos(d.controller().x(),d.controller().y(),d.controller().z())))r.invalidate(d.id(),reason);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void broken(BlockEvent.BreakEvent event){
        if(event.getLevel() instanceof ServerLevel level)at(level,event.getPos(),"observed BreakEvent");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void placed(BlockEvent.EntityPlaceEvent event){
        if(event.getLevel() instanceof ServerLevel level){
            at(level,event.getPos(),"observed EntityPlaceEvent");
            if(event instanceof BlockEvent.EntityMultiPlaceEvent multi)
                for(var snapshot:multi.getReplacedBlockSnapshots())at(level,snapshot.getPos(),"observed EntityMultiPlaceEvent snapshot");
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void explosion(ExplosionEvent.Detonate event){
        if(event.getLevel() instanceof ServerLevel level)for(BlockPos pos:event.getAffectedBlocks())at(level,pos,"observed explosion affected list");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void piston(PistonEvent.Pre event){
        if(!(event.getLevel() instanceof ServerLevel level)||level!=level.getServer().overworld())return;
        FloorEnrollmentEvents.piston(level,event.getPos());
        IeInstallationEvents.piston(level,event.getPos());
        PositionEnrollment r=PositionEnrollment.read(level);if(r==null)return;
        // Conservative superset of a vanilla piston displacement. This can invalidate unaffected nearby levers.
        for(Device d:RegisteredSwitchRegistry.DEVICES)if(d.kind()==Kind.LEVER){
            Pos p=d.controller();if(Math.abs(p.x()-event.getPos().getX())+Math.abs(p.y()-event.getPos().getY())+Math.abs(p.z()-event.getPos().getZ())<=14)
                r.invalidate(d.id(),"near observed piston event; inspect and manually re-enroll");
        }
    }
    private PositionEnrollmentEvents(){}
}
