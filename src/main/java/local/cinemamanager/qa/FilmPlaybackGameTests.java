package local.cinemamanager.qa;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.theater.CinemaProgramme;

import local.cinemamanager.*;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.media.CinemaSourceEvidence;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/**
 * Real loaded manager, TMS, clocks, blocks and NBT. Evidence records are explicit synthetic
 * fixtures; SimulatedViewerTerminal only substitutes the client Ready signal. These tests do
 * NOT authenticate a real network viewer, decode files, render frames, or demonstrate audio.
 */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FilmPlaybackGameTests {
    public static final class SimulatedViewerTerminal extends CinemaTerminalTile {
        boolean ready;
        SimulatedViewerTerminal(BlockPos pos,BlockState state){super(pos,state);}
        @Override public boolean sourceReady(){return ready;}
    }
    static final class Fixture {
        final GameTestHelper h;final ServerPlayer actor;final ManagerTile manager;final CinemaTerminalTile terminal;
        final CinemaProjectorTile first,second;final ManagerState.Hall hall;final FilmCatalog.Film film;
        Fixture(GameTestHelper h,boolean simulated,long duration,int count)throws Exception{this(h,simulated,duration,count,"water://film-runtime-"+UUID.randomUUID());}
        Fixture(GameTestHelper h,boolean simulated,long duration,int count,String source)throws Exception{this(h,simulated,duration,count,source,new BlockPos(1,1,1));}
        Fixture(GameTestHelper h,boolean simulated,long duration,int count,String source,BlockPos managerPos)throws Exception{
            this.h=h;h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"synthetic fixture is GameTest-only");
            actor=PanelGameTests.actor(h);actor.setPos(h.absolutePos(new BlockPos(2,2,2)).getCenter());
            h.setBlock(managerPos,CinemaManager.MANAGER.get());manager=h.getBlockEntity(managerPos);manager.owner=actor.getUUID();manager.state.setTimezone("UTC");
            h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());
            if(simulated){BlockPos pos=h.absolutePos(new BlockPos(4,1,1));h.getLevel().setBlockEntity(new SimulatedViewerTerminal(pos,h.getLevel().getBlockState(pos)));}
            terminal=h.getBlockEntity(new BlockPos(4,1,1));terminal.owner(actor.getUUID());
            h.assertTrue(terminal.data.getUrl()==null&&local.cinemamanager.library.FilmPlayback.session(CinemaSources.snapshot(terminal),true).source().isEmpty(),"fresh TMS empty source retains epoch and permits first programme prepare");
            h.setBlock(new BlockPos(4,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());first=h.getBlockEntity(new BlockPos(4,1,4));first.owner(actor.getUUID());
            h.setBlock(new BlockPos(6,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());second=h.getBlockEntity(new BlockPos(6,1,4));second.owner(actor.getUUID());
            h.assertTrue(terminal.bindNearby(actor)==2,"two reciprocal projectors bound");hall=manager.enroll(actor,terminal,true);
            if(local.cinemamanager.media.MediaSource.isNormalizedLocal(source)){
                // GameTestServer's default operator level is zero. This isolated synthetic actor
                // needs an actual explicit level-four entry to exercise dynamic de-op correctly.
                h.getLevel().getServer().getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(actor.getGameProfile(),4,false));
                h.assertTrue(h.getLevel().getServer().getProfilePermissions(actor.getGameProfile())==4,"test actor has actual explicit level-four OP entry");
            }
            actor.setPos(manager.getBlockPos().getCenter());
            var proof=CinemaSourceEvidence.get(h.getLevel()).remember(actor.getUUID(),terminal.identity(),source,0,duration);
            var draft=manager.library.create(actor,manager.library.catalog().revision(),new FilmCatalog.Draft("Ordered fixture","",Collections.nCopies(count,source)));
            for(int index=0;index<count;index++)draft=manager.library.catalog().observeTrusted(draft.id(),draft.revision(),index,source,false,duration,new FilmCatalog.Provenance(actor.getUUID().toString(),manager.identity.toString(),terminal.identity().toString(),source,proof.id().toString()));
            film=draft;
        }
        void authorize(){manager.authorizeProgramme(actor,hall.id,true);}
        void load(){manager.playback.load(actor,hall.id,film.id(),film.revision(),true);}
        ManagerState.Hall view(){var copy=StateCodec.decode(StateCodec.encode(manager.state));manager.describeSeek(actor,copy,false);return copy.hall(hall.id);}
        void ready(boolean value){((SimulatedViewerTerminal)terminal).ready=value;}
        void pair(){h.assertTrue(first.terminal()==terminal&&second.terminal()==terminal&&first.clock()==null&&second.clock()==null,"only the TMS owns a clock for both projectors");}
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void film_existing_grant_and_real_no_viewer_hold(GameTestHelper h)throws Exception{
        Fixture f=new Fixture(h,false,6000,2);
        PanelGameTests.rejects(h,()->f.load(),"load cannot silently grant offline programme control");
        h.assertTrue(f.terminal.automationGrant()==null&&!f.manager.state.schedulerEnabled,"failed load changes no grant or scheduling");
        f.authorize();f.load();
        h.assertTrue(!f.manager.state.schedulerEnabled&&f.hall.override,"manual whole-film load does not enable global scheduler");
        h.runAfterDelay(12,()->{
            h.assertTrue(f.terminal.clock()!=null&&f.terminal.clock().paused()&&f.terminal.clock().time()==0,"real TMS stays paused without an authenticated viewer");
            h.assertTrue(!CinemaSources.snapshot(f.terminal).ready()&&f.view().seekToken.isBlank(),"saved owner evidence does not invent viewer readiness");
            f.terminal.data.paused=false;
            h.runAfterDelay(8,()->{h.assertTrue(f.terminal.clock().paused()&&f.terminal.clock().time()==0,"WF hold prevents raw play intent bypass");f.pair();h.succeed();});
        });
    }
    @GameTest(template="empty",timeoutTicks=1000000)
    public static void film_current_clip_seek_explicit_selection_and_reload_intent(GameTestHelper h)throws Exception{
        Fixture f=new Fixture(h,true,6000,2);f.ready(true);f.authorize();f.load();
        h.runAfterDelay(16,()->{
            var before=f.view();var firstClock=f.terminal.clock();UUID firstEpoch=f.terminal.mediaEpoch();
            h.assertTrue(before.durationMs==15000&&!before.seekToken.isBlank()&&firstClock.paused(),"programme includes one gap while its seek ticket addresses the finite current clip");
            f.manager.seek(f.actor,f.hall.id,before.seekToken,3000);
            PanelGameTests.rejects(h,()->f.manager.seek(f.actor,f.hall.id,before.seekToken,1000),"current-clip ticket is one-use");
            h.assertTrue(f.terminal.clock()==firstClock&&firstEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().time()==3000,"same-clip seek never rotates or replaces its source");
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.CHOOSE,1);
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PLAY);
            PlaylistTransitionGameTests.await(h,System.nanoTime()+20_000_000_000L,
                    ()->PlaylistTransitionGameTests.view(f).currentIndex()==1&&PlaylistTransitionGameTests.view(f).status().equals("PLAYING"),()->{
                PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PAUSE);
                f.manager.seek(f.actor,f.hall.id,f.view().seekToken,3000);
                h.assertTrue(f.terminal.clock()!=firstClock&&!firstEpoch.equals(f.terminal.mediaEpoch()),"explicit selection of identical source still gets a distinct clock epoch");
                h.assertTrue(f.view().playheadMs==12000&&f.terminal.clock().time()==3000&&f.terminal.clock().paused(),"selected clip keeps its local offset while programme progress includes the full gap");f.pair();
                var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
                f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
                h.runAfterDelay(12,()->{
                    h.assertTrue(f.terminal.clock().paused()&&f.view().seekToken.isBlank(),"cold programme restoration waits for a new Ready");
                    f.ready(true);
                    h.runAfterDelay(12,()->{h.assertTrue(f.view().playheadMs==12000&&f.terminal.clock().time()==3000&&f.terminal.clock().paused(),"verified surviving lease restores same occurrence and paused local time");f.pair();h.succeed();});
                });
            },"explicit selection did not finish its three-second gap");
        });
    }
    @GameTest(template="empty",timeoutTicks=75)
    public static void film_external_source_takeover_never_halts_replacement(GameTestHelper h)throws Exception{
        Fixture f=new Fixture(h,true,6000,2);f.ready(true);f.authorize();f.load();
        h.runAfterDelay(16,()->{
            var old=f.view();f.terminal.data.url="water://external-replacement-"+UUID.randomUUID();f.terminal.sourceReplaced();f.terminal.data.active=true;f.terminal.data.paused=true;f.terminal.setDirty();
            PanelGameTests.rejects(h,()->f.manager.seek(f.actor,f.hall.id,old.seekToken,1000),"old programme cannot seek a replacement epoch");
            h.runAfterDelay(12,()->{h.assertTrue(f.terminal.data.url.startsWith("water://external-replacement-")&&f.terminal.clock()!=null&&f.terminal.clock().paused(),"old programme does not replace or halt external source");h.assertFalse(f.manager.playback.active(f.hall.id),"programme takeover is final");h.succeed();});
        });
    }
    @GameTest(template="empty",timeoutTicks=1000000)
    public static void film_repeated_order_completes_once_and_stays_final_after_reload(GameTestHelper h)throws Exception{
        Fixture f=new Fixture(h,true,300,2);f.ready(true);f.authorize();f.load();
        h.runAfterDelay(16,()->{
            UUID initial=f.terminal.mediaEpoch();PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PLAY);
            // GameTest runs simulation ticks faster than wall time; the media clock uses real time.
            // Wait for the actual natural Ended transition instead of treating 42 test ticks as 2 s.
            final long deadline=System.nanoTime()+15_000_000_000L;
            h.runAfterDelay(5,new Runnable(){int checks;public void run(){
                if(++checks%500==0)System.out.println("FILM_END_WAIT clock="+f.terminal.clock().snapshot()+" dataPaused="+f.terminal.data.paused+" epoch="+f.terminal.mediaEpoch());
                if(f.manager.playback.active(f.hall.id)&&System.nanoTime()<deadline){h.runAfterDelay(5,()->this.run());return;}
                var diagnostics=new net.minecraft.nbt.CompoundTag();f.manager.playback.save(diagnostics);
                h.assertFalse(f.manager.playback.active(f.hall.id),"two short repeated segments finish exactly once: clock="+f.terminal.clock().snapshot()+" pausedIntent="+f.terminal.data.paused+" programme="+diagnostics);
                h.assertTrue(f.view().playheadMs==3600&&f.terminal.clock().ended()&&!initial.equals(f.terminal.mediaEpoch()),"natural Ended advanced once then ended the programme");
                UUID finalEpoch=f.terminal.mediaEpoch();var saved=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());f.manager.loadWithComponents(saved,h.getLevel().registryAccess());
                h.runAfterDelay(12,()->{h.assertTrue(finalEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().ended()&&!f.manager.playback.active(f.hall.id),"restored completed programme creates no new source or playback");f.pair();h.succeed();});
            }});
        });
    }
}
