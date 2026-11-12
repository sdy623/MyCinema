package local.cinemamanager.qa;

import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Uses GameTest's test player only as a permission fixture; production has no fake-player path. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class SeekGameTests {
    private record Fixture(ManagerTile manager,CinemaTerminalTile terminal,CinemaProjectorTile first,CinemaProjectorTile second,ServerPlayer actor,ManagerState.Hall hall) {}
    private static Fixture fixture(GameTestHelper h){
        ServerPlayer actor=h.makeMockServerPlayerInLevel();actor.setPos(h.absolutePos(new BlockPos(2,2,2)).getCenter());
        h.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile m=h.getBlockEntity(new BlockPos(1,1,1));m.owner=actor.getUUID();
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile t=h.getBlockEntity(new BlockPos(4,1,1));t.owner(actor.getUUID());
        h.setBlock(new BlockPos(4,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());CinemaProjectorTile a=h.getBlockEntity(new BlockPos(4,1,4));a.owner(actor.getUUID());
        h.setBlock(new BlockPos(6,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());CinemaProjectorTile b=h.getBlockEntity(new BlockPos(6,1,4));b.owner(actor.getUUID());
        h.assertTrue(t.bindNearby(actor)==2,"real binding API links both projectors");
        ManagerState.Hall hall=m.enroll(actor,t,true);
        t.data.url="water://mycinema-seek-fixture";t.data.uuid=actor.getUUID();t.data.active=true;t.data.paused=true;t.setDirty();
        return new Fixture(m,t,a,b,actor,hall);
    }
    private static ManagerState.Hall view(Fixture f){var state=StateCodec.decode(StateCodec.publicView(f.manager.state));f.manager.describeSeek(f.actor,state,false);return state.halls.getFirst();}
    private static void rejects(GameTestHelper h,Runnable run,String reason){boolean rejected=false;try{run.run();}catch(IllegalArgumentException e){rejected=true;}h.assertTrue(rejected,reason);}
    @GameTest(template="empty",timeoutTicks=90)
    public static void shared_tms_seek_preserves_pause_and_play(GameTestHelper h){
        Fixture f=fixture(h);
        h.runAfterDelay(6,()->{
            var t=f.terminal;var clock=t.clock();h.assertTrue(clock!=null&&clock.paused(),"paused server session opened");
            h.assertTrue(view(f).seekToken.isBlank(),"unknown duration cannot issue seek lease");
            clock.syncDuration(6000);var before=view(f);h.assertTrue(!before.seekToken.isBlank(),"trusted current VOD gets a lease");
            ManagerState.Show show=new ManagerState.Show();show.hallId=f.hall.id;show.title="running fixture";show.url=t.data.url;show.status=ManagerState.Status.RUNNING;show.start=System.currentTimeMillis()-1000;show.end=System.currentTimeMillis()+60000;f.manager.state.shows.add(show);
            f.manager.seek(f.actor,f.hall.id,before.seekToken,2856);
            h.assertTrue(t.clock()==clock&&clock.paused()&&t.data.paused&&clock.time()==2856,"seek preserves exact paused clock and position");
            h.assertTrue(f.hall.override&&show.status==ManagerState.Status.OVERRIDDEN,"seek takes manual control of active show");
            h.assertTrue(f.first.clock()==null&&f.second.clock()==null&&f.first.terminal()==t&&f.second.terminal()==t,"both projectors still share the TMS only");
            rejects(h,()->f.manager.seek(f.actor,f.hall.id,before.seekToken,1000),"consumed lease cannot replay");
            var refresh=view(f);var secondRefresh=view(f);f.manager.seek(f.actor,f.hall.id,refresh.seekToken,3611);h.assertTrue(clock.time()==3611,"refresh does not revoke held lease");
            rejects(h,()->f.manager.seek(f.actor,f.hall.id,secondRefresh.seekToken,4000),"intervening seek rejects older revision");
            t.data.paused=false;clock.pause(false);var playing=view(f);f.manager.seek(f.actor,f.hall.id,playing.seekToken,1000);
            h.assertTrue(!t.data.paused&&clock.playing(),"playing seek stays playing");long position=clock.time();
            h.runAfterDelay(5,()->{h.assertTrue(clock.time()>position&&t.clock()==clock,"same playing clock advances after seek");
                CompoundTag save=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());h.assertFalse(local.cinemamanager.library.CatalogStorage.read(save,"manager_state_chunks").contains(playing.seekToken),"actor lease is never persisted");h.succeed();});
        });
    }
    @GameTest(template="empty",timeoutTicks=70)
    public static void seek_rechecks_permission_source_and_tablet(GameTestHelper h){
        Fixture f=fixture(h);
        h.runAfterDelay(6,()->{
            var t=f.terminal;var clock=t.clock();clock.syncDuration(6000);
            var local=view(f);f.manager.owner=UUID.randomUUID();rejects(h,()->f.manager.seek(f.actor,f.hall.id,local.seekToken,2000),"revoked manager owner denied");f.manager.owner=f.actor.getUUID();
            var owned=view(f);t.owner(UUID.randomUUID());rejects(h,()->f.manager.seek(f.actor,f.hall.id,owned.seekToken,2000),"revoked TMS owner denied");t.owner(f.actor.getUUID());
            var source=view(f);t.data.url="water://replacement";rejects(h,()->f.manager.seek(f.actor,f.hall.id,source.seekToken,2000),"new URL rejects old clock before next tick");t.data.url="water://mycinema-seek-fixture";
            var stale=view(f);clock.seek(4000);rejects(h,()->f.manager.seek(f.actor,f.hall.id,stale.seekToken,2000),"other TMS control rejects stale revision");
            clock.live(true);h.assertTrue(view(f).seekToken.isBlank(),"live source disables seek");clock.live(false);
            // Move away from local range while staying in this loaded test dimension.
            f.actor.setPos(f.manager.getBlockPos().getCenter().add(20,1,0));h.assertTrue(view(f).seekToken.isBlank(),"no remote control without held bound tablet");
            ItemStack tablet=new ItemStack(CinemaManager.TABLET.get());CompoundTag binding=new CompoundTag();binding.putLong("manager",f.manager.getBlockPos().asLong());binding.putUUID("identity",f.manager.identity);binding.putString("dimension",h.getLevel().dimension().location().toString());tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(binding));f.actor.setItemInHand(InteractionHand.MAIN_HAND,tablet);
            var remote=view(f);h.assertTrue(!remote.seekToken.isBlank(),"current held tablet permits loaded remote hall control");
            f.actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);rejects(h,()->f.manager.seek(f.actor,f.hall.id,remote.seekToken,3000),"removing tablet before release revokes remote seek");
            f.actor.setItemInHand(InteractionHand.MAIN_HAND,tablet);var remoteAgain=view(f);f.manager.seek(f.actor,f.hall.id,remoteAgain.seekToken,3000);h.assertTrue(clock.time()==3000&&clock.paused(),"remote seek reaches same paused clock");
            h.succeed();
        });
    }
}
