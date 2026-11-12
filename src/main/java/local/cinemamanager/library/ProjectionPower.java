package local.cinemamanager.library;
import local.cinemamanager.media.CinemaSources;

import local.cinemamanager.ManagerTile;
import local.cinemamanager.policy.CinemaPermissions;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Only current enrolled, owner-controlled reciprocal outputs; no discovery or chunk loading. */
public final class ProjectionPower {
    private ProjectionPower() {}
    public static String check(ManagerTile manager,CinemaTerminalTile terminal,ServerPlayer actor,UUID epoch) {
        try { members(manager,terminal,actor,epoch);return null; }
        catch(RuntimeException denied){
            if(Boolean.getBoolean("mycinema.qa"))org.slf4j.LoggerFactory.getLogger("MyCinema/ProjectionPower").warn("QA projector group rejected: {}",denied.getMessage());
            return "projection_unavailable";
        }
    }
    public static String apply(ManagerTile manager,CinemaTerminalTile terminal,ServerPlayer actor,UUID epoch,boolean enabled) {
        try {
            // Validate all recorded members before changing any. Server-thread execution is atomic.
            var pair=members(manager,terminal,actor,epoch);
            for(var member:pair){member.data.active=enabled;member.setDirty();}
            return null;
        } catch(RuntimeException denied){return "projection_unavailable";}
    }
    private static List<CinemaProjectorTile> members(ManagerTile manager,CinemaTerminalTile terminal,ServerPlayer actor,UUID epoch) {
        if(!(manager.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()
                ||manager.isRemoved()||!level.hasChunkAt(manager.getBlockPos())||level.getBlockEntity(manager.getBlockPos())!=manager)
            throw new IllegalStateException("manager unavailable");
        if(terminal==null||terminal.getLevel()!=level||terminal.isRemoved()||manager.owner==null
                ||!level.hasChunkAt(terminal.getBlockPos())||level.getBlockEntity(terminal.getBlockPos())!=terminal
                ||!terminal.ownedBy(manager.owner))throw new IllegalStateException("terminal unavailable or ownership changed");
        var hall=manager.state.halls.stream().filter(h->h.terminalIdentity.equals(terminal.identity().toString())).findFirst().orElseThrow();
        if(manager.programmeTerminal(hall.id)!=terminal)throw new IllegalStateException("terminal enrollment changed");
        if(epoch!=null&&(!epoch.equals(terminal.mediaEpoch())||!CinemaSources.controlledBy(terminal,
                new CinemaSources.ControllerRef(manager.getBlockPos(),manager.identity))))throw new IllegalStateException("media epoch changed");
        var grant=terminal.automationGrant();
        var online=level.getServer().getPlayerList().getPlayer(manager.owner);
        if(actor!=null){
            if(!manager.owner.equals(actor.getUUID())||!manager.theaterSourceControlAllowed(actor,terminal.identity()))throw new IllegalStateException("actor lost theater source permission");
        }else if(grant==null||!grant.matches(terminal.identity(),manager.identity,manager.owner,manager.getBlockPos())
                ||!CinemaPermissions.canAutomateTheater(manager.owner,online==null?grant.mode():online.gameMode.getGameModeForPlayer(),terminal.getDisplayBlock(),"",false))throw new IllegalStateException("automation grant unavailable");
        // WF supports a real one-projector theater too. Never drop a missing/stale
        // recorded second member to manufacture that case: validate every entry below.
        int count=terminal.projectors().size();
        if(count<1||count>2)throw new IllegalStateException("projector count is "+count);
        List<CinemaProjectorTile> pair=new ArrayList<>();Set<UUID> ids=new HashSet<>();
        for(var link:terminal.projectors().entrySet()){
            if(!level.hasChunkAt(link.getKey()))throw new IllegalStateException("projector chunk unloaded at "+link.getKey());
            if(!(level.getBlockEntity(link.getKey()) instanceof CinemaProjectorTile member))throw new IllegalStateException("projector missing at "+link.getKey());
            if(member.isRemoved())throw new IllegalStateException("projector removed at "+link.getKey());
            if(!member.identity().equals(link.getValue()))throw new IllegalStateException("projector identity changed at "+link.getKey());
            if(!ids.add(member.identity()))throw new IllegalStateException("duplicate projector identity at "+link.getKey());
            if(!terminal.contains(member))throw new IllegalStateException("terminal link missing for projector at "+link.getKey());
            if(!member.linkedTo(terminal)||member.terminal()!=terminal)throw new IllegalStateException("projector reciprocal link missing at "+link.getKey());
            if(!member.ownedBy(manager.owner))throw new IllegalStateException("projector ownership changed at "+link.getKey());
            if(actor!=null?!member.allows(actor):!CinemaPermissions.canAutomateTheater(manager.owner,
                    online==null?grant.mode():online.gameMode.getGameModeForPlayer(),member.getDisplayBlock(),"",false))throw new IllegalStateException("projector permission denied at "+link.getKey());
            pair.add(member);
        }
        return pair;
    }
}
