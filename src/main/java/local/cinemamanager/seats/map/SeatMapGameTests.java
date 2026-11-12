package local.cinemamanager.seats.map;

import java.util.*;
import com.mojang.authlib.GameProfile;
import local.cinemamanager.*;
import local.cinemamanager.seats.*;
import local.cinemamanager.panels.PanelScope;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;
import static local.cinemamanager.seats.map.SeatMapSnapshot.*;

/** Real registry/chair/authority acceptance checks, authored but not run at the source-only gate. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class SeatMapGameTests {
    private static ServerPlayer actor(GameTestHelper h,String name){var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));p.setGameMode(GameType.SURVIVAL);p.setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());return p;}
    private static void reject(GameTestHelper h,Runnable action,String message){boolean bad=false;try{action.run();}catch(IllegalArgumentException expected){bad=true;}h.assertTrue(bad,message);}
    private static SeatMapNetwork.Request request(OwnedTile tile){return new SeatMapNetwork.Request(tile.getBlockPos(),tile.identity.toString(),UUID.randomUUID().toString(),"open","{}");}
    @GameTest(template="empty",timeoutTicks=40)
    public static void seat_map_loaded_positions_identity_and_public_scope(GameTestHelper h){
        ServerPlayer owner=actor(h,"MapOwnerQA"),guest=actor(h,"MapGuestQA");
        h.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(new BlockPos(1,1,1));manager.initializePlacement(owner.getUUID());
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile terminal=h.getBlockEntity(new BlockPos(4,1,1));terminal.owner(owner.getUUID());var hall=manager.enroll(owner,terminal,true);
        h.assertTrue(SeatMapNetwork.managerAllowed(owner,manager),"near current owner can read map");
        reject(h,()->SeatMapNetwork.authorize(guest,request(manager),hall.id),"guest cannot use manager as public authority");
        BlockPos chair=h.absolutePos(new BlockPos(2,1,4));h.getLevel().setBlockAndUpdate(chair.below(),Blocks.STONE.defaultBlockState());h.getLevel().setBlockAndUpdate(chair,SeatsModule.CHAIR.get().defaultBlockState());
        CinemaSeatTile tile=(CinemaSeatTile)h.getLevel().getBlockEntity(chair);tile.bindController(manager.identity);
        SeatRegistry registry=SeatStorage.get(h.getLevel(),manager.identity).registry();SeatRegistry.Seat entry=registry.put(0,"",hall.id,"Whole row",1,1,chair.asLong(),SeatRegistry.Status.MAINTENANCE,SeatRegistry.PHYSICAL_BLOCK,tile.seatUid().toString(),GuidePreset.RowScheme.LETTERS);
        var snap=SeatMapServer.capture(owner,manager,hall.id,Optional.empty());
        h.assertTrue(snap.seats().getFirst().position().packed()==chair.asLong()&&snap.seats().getFirst().proof()==ProofStatus.VALID,"capture uses canonical installed position and UID");
        var publicView=SeatMapServer.project(snap,manager.identity,hall.id,"","");var availability=publicView.seats().getFirst().availability();
        h.assertTrue(availability.serviceability()==SeatRegistry.Status.MAINTENANCE&&availability.occupancy()==Occupancy.EMPTY&&availability.tickets()==TicketAvailability.UNKNOWN,"physical occupancy, serviceability and admission are independent");
        Bounds bounds=new Bounds(chair.getX()-1,chair.getY(),chair.getZ()-1,chair.getX()+1,chair.getY(),chair.getZ()+1);
        Polygon polygon=new Polygon(List.of(new Point(bounds.minX(),bounds.minZ()),new Point(bounds.maxX(),bounds.minZ()),new Point(bounds.maxX(),bounds.maxZ()),new Point(bounds.minX(),bounds.maxZ())));
        Layout authoredLayout=new Layout(1,bounds,List.of(new Floor("f","Explicit floor",chair.getY(),chair.getY())),List.of(new Zone("z","f","Left section",polygon)),List.of(),List.of());
        var authored=new SeatMapLayoutDocument(1,new Scope(manager.identity.toString(),hall.id,h.getLevel().dimension().location().toString()),authoredLayout,new Transform(new Point(chair.getX(),chair.getZ()),Orientation.NORTH,1),List.of(new SeatMapLayoutDocument.Assignment(chair.asLong(),tile.seatUid().toString(),"f","z")));
        SeatMapStorage geometry=SeatMapStorage.get(h.getLevel(),manager.identity,hall.id);
        reject(h,()->geometry.importAuthored(owner,manager,hall.id,0,0,authored.encode()),"stale registry cannot import");
        var wrongScope=new SeatMapLayoutDocument(1,new Scope(UUID.randomUUID().toString(),hall.id,h.getLevel().dimension().location().toString()),authoredLayout,authored.transform(),authored.assignments());
        reject(h,()->geometry.importAuthored(owner,manager,hall.id,1,0,wrongScope.encode()),"foreign controller scope cannot import");
        h.assertTrue(geometry.document().isEmpty()&&registry.revision()==1,"failed imports mutate neither layout nor numbering");
        geometry.importAuthored(owner,manager,hall.id,1,0,authored.encode());
        reject(h,()->geometry.importAuthored(owner,manager,hall.id,1,0,authored.encode()),"stale layout cannot overwrite imported geometry");
        h.assertTrue(geometry.document().orElseThrow().equals(authored)&&registry.revision()==1,"successful explicit layout import preserves registry");
        BlockPos distant=new BlockPos(20000000,70,20000000);SeatRegistry.Seat remote=new SeatRegistry.Seat(UUID.randomUUID().toString(),hall.id,"Whole row",1,2,distant.asLong(),SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,UUID.randomUUID().toString(),GuidePreset.RowScheme.NUMERIC);
        h.assertFalse(h.getLevel().hasChunkAt(distant),"remote fixture unloaded");h.assertTrue(SeatMapServer.observe(h.getLevel(),manager.identity,remote).block()==ObservedBlock.UNLOADED&&!h.getLevel().hasChunkAt(distant),"observation cannot load remote chunk");
        h.getLevel().setBlockAndUpdate(chair,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(chair,SeatsModule.CHAIR.get().defaultBlockState());CinemaSeatTile replacement=(CinemaSeatTile)h.getLevel().getBlockEntity(chair);replacement.bindController(manager.identity);
        h.assertTrue(SeatMapServer.capture(owner,manager,hall.id,Optional.empty()).seats().getFirst().proof()==ProofStatus.REPLACED,"replacement never adopts old chair UID");
        h.setBlock(new BlockPos(3,1,1),CinemaManager.INFO.get());InfoTile info=h.getBlockEntity(new BlockPos(3,1,1));info.manager=manager.getBlockPos();info.managerIdentity=manager.identity;info.hallId=hall.id;info.scope=PanelScope.HALL;
        h.assertTrue(SeatMapNetwork.authorize(guest,request(info),UUID.randomUUID().toString()).hall().equals(hall.id),"public panel cannot select another hall");
        String initialSourceStamp=SeatMapNetwork.authorize(guest,request(info),hall.id).sourceStamp();
        info.terminalRole=local.cinemamanager.operations.TerminalRoleSettings.OPERATIONS;info.panelRevision++;
        reject(h,()->SeatMapNetwork.authorize(guest,request(info),hall.id),"operations panel cannot serve public seat map requests");
        info.terminalRole=local.cinemamanager.operations.TerminalRoleSettings.LEGACY;info.panelRevision++;
        h.assertFalse(initialSourceStamp.equals(SeatMapNetwork.authorize(guest,request(info),hall.id).sourceStamp()),"role away and back invalidates previous public map authority stamp");
        var guestPosition=guest.position();guest.setPos(manager.getBlockPos().getCenter().add(100,0,0));
        reject(h,()->SeatMapNetwork.authorize(guest,request(info),hall.id),"guest reach enforced on bound public panel");guest.setPos(guestPosition);
        info.managerIdentity=UUID.randomUUID();reject(h,()->SeatMapNetwork.authorize(guest,request(info),hall.id),"stale panel binding fails");info.managerIdentity=manager.identity;
        h.setBlock(new BlockPos(5,1,1),CinemaManager.AD_LED.get());InfoTile ad=h.getBlockEntity(new BlockPos(5,1,1));ad.manager=manager.getBlockPos();ad.managerIdentity=manager.identity;ad.hallId=hall.id;ad.scope=PanelScope.HALL;
        reject(h,()->SeatMapNetwork.authorize(guest,request(ad),hall.id),"advertising block never becomes seat map public authority");
        SeatMapNetwork.Args args=new SeatMapNetwork.Args();args.hall=hall.id;String requestId=UUID.randomUUID().toString();
        SeatMapNetwork.apply(owner,new SeatMapNetwork.Request(manager.getBlockPos(),manager.identity.toString(),requestId,"open",SeatRegistry.JSON.toJson(args)));
        h.assertTrue(SeatMapNetwork.hasSession(owner),"open allocates bounded snapshot for current actor and connection");
        ServerPlayer reconnected=new FakePlayer(h.getLevel(),new GameProfile(owner.getUUID(),"MapReconnectQA"));
        h.assertFalse(SeatMapNetwork.hasSession(reconnected),"same UUID with new connection cannot resume old session");
        SeatMapNetwork.disconnect(reconnected);h.assertTrue(SeatMapNetwork.hasSession(owner),"unrelated delayed disconnect cannot remove current connection");
        SeatMapNetwork.disconnect(owner);h.assertFalse(SeatMapNetwork.hasSession(owner),"logout releases snapshot slot immediately");
        SeatMapNetwork.apply(owner,new SeatMapNetwork.Request(manager.getBlockPos(),manager.identity.toString(),requestId,"open",SeatRegistry.JSON.toJson(args)));
        args.snapshot=SeatMapNetwork.activeSnapshot(owner);args.index=0;
        manager.owner=guest.getUUID();
        SeatMapNetwork.apply(owner,new SeatMapNetwork.Request(manager.getBlockPos(),manager.identity.toString(),requestId,"page",SeatRegistry.JSON.toJson(args)));
        h.assertFalse(SeatMapNetwork.hasSession(owner),"page after ownership revocation destroys old authorized snapshot");
        h.assertFalse(SeatMapNetwork.managerAllowed(owner,manager),"ownership revocation applies immediately");
        CompoundTag future=new CompoundTag();future.putInt("version",999);future.putString("document","future malformed content");future.putString("unknown","preserve me");
        SeatMapStorage preserved=SeatMapStorage.load(future,h.getLevel().registryAccess());h.assertFalse(preserved.readable(),"future state fails closed");h.assertTrue(preserved.save(new CompoundTag(),h.getLevel().registryAccess()).equals(future),"future NBT retained without data loss");
        CompoundTag extra=new CompoundTag();extra.putInt("version",1);extra.putString("document",authored.encode());extra.putString("future_extra","retain");
        SeatMapStorage unknown=SeatMapStorage.load(extra,h.getLevel().registryAccess());h.assertFalse(unknown.readable(),"unknown version-one extension fails closed");h.assertTrue(unknown.save(new CompoundTag(),h.getLevel().registryAccess()).equals(extra),"unknown version-one fields preserved");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void seat_map_double_support_variant_keeps_physical_identity(GameTestHelper h){
        ServerPlayer owner=actor(h,"VariantMapQA");
        h.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(new BlockPos(1,1,1));manager.initializePlacement(owner.getUUID());
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile terminal=h.getBlockEntity(new BlockPos(4,1,1));terminal.owner(owner.getUUID());var hall=manager.enroll(owner,terminal,true);
        BlockPos pos=h.absolutePos(new BlockPos(2,1,4));h.getLevel().setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        h.getLevel().setBlockAndUpdate(pos,SeatsModule.DOUBLE_SUPPORT_CHAIR.get().defaultBlockState());
        CinemaSeatTile tile=CinemaSeatBlock.findSeat(h.getLevel(),pos).orElseThrow();tile.bindController(manager.identity);
        SeatRegistry registry=SeatStorage.get(h.getLevel(),manager.identity).registry();
        registry.put(0,"",hall.id,"Center",1,1,pos.asLong(),SeatRegistry.Status.AVAILABLE,SeatRegistry.DOUBLE_SUPPORT_BLOCK,tile.seatUid().toString(),GuidePreset.RowScheme.LETTERS);
        var snapshot=SeatMapServer.capture(owner,manager,hall.id,Optional.empty());
        h.assertTrue(snapshot.seats().getFirst().proof()==ProofStatus.VALID,"new variant is an actual verified chair, not unsupported legacy furniture");
        var view=SeatMapServer.project(snapshot,manager.identity,hall.id,"","");
        h.assertTrue(view.seats().getFirst().availability().tickets()==TicketAvailability.UNKNOWN,"new artwork does not invent ticket availability");
        h.succeed();
    }

}
