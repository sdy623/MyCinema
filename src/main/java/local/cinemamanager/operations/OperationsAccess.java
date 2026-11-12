package local.cinemamanager.operations;

import local.cinemamanager.*;
import local.cinemamanager.panels.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.*;

/** Rebuilds facts for every request. Cards, creative inventory and client role fields are never authority. */
public final class OperationsAccess {
    private OperationsAccess(){}
    public static ManagerTile requireView(ServerPlayer actor,InfoTile tile){
        if(actor==null||tile==null||actor instanceof FakePlayer||actor.connection==null||!(tile.getLevel() instanceof ServerLevel level)
                ||!level.getServer().isSameThread()||level.getServer().getPlayerList().getPlayer(actor.getUUID())!=actor)throw denied("permission");
        if(!PanelNetwork.guestAllowed(actor,tile)||actor.isSpectator())throw denied("permission");
        InfoTile root=PanelGroup.of(tile).root();
        if(!root.isOperations())throw denied(root.invalidTerminalRole()?"invalid_role":"not_operations");
        ManagerTile manager=root.controller();
        if(manager==null||manager.isRemoved()||manager.getLevel()!=level||level.getBlockEntity(manager.getBlockPos())!=manager)throw denied("unavailable");
        UUID hall=root.scope==PanelScope.HALL?uuid(root.hallId):null;
        var expected=new OperationsPolicy.Expected(root.identity,root.panelRevision,manager.identity,hall,null,0,0);
        var decision=OperationsPolicy.authorize(
                new OperationsPolicy.Actor(actor.getUUID(),level.dimension().location().toString(),true,actor.isSpectator(),level.mayInteract(actor,root.getBlockPos()),tile.panelDistanceSquared(actor)),
                new OperationsPolicy.Controller(manager.identity,manager.owner,level.dimension().location().toString(),0,true,manager.state.halls.stream().map(h->uuid(h.id)).collect(java.util.stream.Collectors.toSet())),
                new OperationsPolicy.Panel(root.identity,root.owner,BuiltInRegistries.BLOCK.getKey(root.getBlockState().getBlock()).toString(),level.dimension().location().toString(),root.managerIdentity,hall,root.panelRevision,true,root.scope,root.terminalRole),
                null,OperationsPolicy.Action.VIEW,expected,null,System.currentTimeMillis());
        // Future staff delegation must supply a real server-owned grant here. None exists in this release.
        if(!decision.permits(OperationsPolicy.Action.VIEW,expected))throw denied(decision.denial()==OperationsPolicy.Denial.NO_GRANT?"owner_required":decision.denial().name().toLowerCase(Locale.ROOT));
        if(!manager.permits(actor,Double.POSITIVE_INFINITY))throw denied("permission");
        return manager;
    }
    public static ManagerTile requireControl(ServerPlayer actor,InfoTile tile,OperationsNetwork.Arguments args){
        ManagerTile manager=requireView(actor,tile);InfoTile root=PanelGroup.of(tile).root();
        if(args==null||!manager.identity.toString().equals(args.controller)||args.panelRevision!=root.panelRevision
                ||!PanelGroup.of(tile).signature().equals(args.signature))throw denied("stale_panel");
        if(!controlReach(actor,manager))throw denied("manager_range");
        var hall=manager.state.halls.stream().filter(h->Objects.equals(h.id,args.hall)).findFirst().orElseThrow(()->denied("wrong_hall"));
        if(!PublicOperationsSnapshot.includes(root,hall))throw denied("wrong_hall");
        return manager;
    }
    /** New console entry requires a live registered tablet; legacy manager-item bindings stay unchanged. */
    public static boolean controlReach(ServerPlayer actor,ManagerTile manager){
        return manager.controlAllowed(actor)&&(manager.permits(actor,8)||local.cinemamanager.access.TabletAccess.registeredHeld(actor,manager));
    }
    public static UUID uuid(String raw){try{return UUID.fromString(raw);}catch(RuntimeException invalid){throw denied("wrong_hall");}}
    public static IllegalArgumentException denied(String reason){return new IllegalArgumentException("mycinema.operations."+reason);}
}
