package local.cinemamanager.qa;

import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.policy.CinemaPermissions;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.media.MediaSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Isolated synthetic plans only. No decoder, external URL or user world is used. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ScheduleGameTests {
    private record Fixture(ManagerTile manager,ServerPlayer owner,CinemaTerminalTile terminal,ManagerState.Hall hall,CinemaTerminalTile other,ManagerState.Hall otherHall) {}
    private static Fixture fixture(GameTestHelper h){
        var actor=PanelGameTests.actor(h);
        h.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile m=h.getBlockEntity(new BlockPos(1,1,1));m.owner=actor.getUUID();m.state.setTimezone("UTC");
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile t=h.getBlockEntity(new BlockPos(4,1,1));t.owner(actor.getUUID());
        h.setBlock(new BlockPos(4,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());CinemaProjectorTile projector=h.getBlockEntity(new BlockPos(4,1,4));projector.owner(actor.getUUID());t.bindNearby(actor);
        var hall=m.enroll(actor,t,true);
        h.setBlock(new BlockPos(7,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile other=h.getBlockEntity(new BlockPos(7,1,1));other.owner(actor.getUUID());var otherHall=m.enroll(actor,other,true);
        t.data.url="water://schedule-current";t.data.uuid=actor.getUUID();t.data.active=true;t.data.paused=false;t.setDirty();
        other.data.url="water://other-current";other.data.uuid=actor.getUUID();other.data.active=true;other.data.paused=true;other.setDirty();
        return new Fixture(m,actor,t,hall,other,otherHall);
    }
    private static ManagerState.Show plan(Fixture f,ManagerState.Hall hall,String title,String source,int offset){
        long now=System.currentTimeMillis();return f.manager.state.addShow(hall.id,title,source,f.manager.state.inputTime((now/60000+offset)*60000),1,now);
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void saved_show_load_uses_server_source_and_preserves_future_plan(GameTestHelper h){
        Fixture f=fixture(h);var future=plan(f,f.hall,"Saved film","https://imgur.com/synthetic 影片 Test.mkv",10);var other=plan(f,f.otherHall,"Other film","water://other-plan",10);
        var active=new ManagerState.Show();active.hallId=f.hall.id;active.title="Active film";active.url=f.terminal.data.url;active.start=System.currentTimeMillis()-1000;active.end=System.currentTimeMillis()+60000;active.status=ManagerState.Status.RUNNING;f.manager.state.shows.add(active);
        h.assertFalse(CinemaPermissions.canSave(f.owner,future.url),"raw spaces fail whitelist URI parsing for the ordinary owner");
        h.assertTrue(CinemaPermissions.canSave(f.owner,MediaSource.normalize(future.url)),"canonical stored source retains the allowed host");
        String source=future.url;long start=future.start,end=future.end;String otherSource=f.other.data.url;
        h.runAfterDelay(5,()->{
            var previous=f.terminal.clock();h.assertTrue(previous!=null&&previous.playing(),"old media clock exists before explicit load");
            long revision=f.manager.state.revision;f.manager.loadShow(f.owner,future.id,revision);
            h.assertTrue(f.terminal.data.url.equals(MediaSource.normalize(source))&&f.terminal.data.active&&f.terminal.data.paused,"saved server source is normalized and loaded paused");
            h.assertTrue(previous.stopped()&&active.status==ManagerState.Status.OVERRIDDEN&&f.hall.override,"loading replaces current playback and explicitly takes manual control");
            h.assertTrue(future.url.equals(source)&&future.start==start&&future.end==end&&future.status==ManagerState.Status.PENDING,"future plan source/time/status are not rewritten by loading");
            h.assertTrue(f.other.data.url.equals(otherSource)&&other.status==ManagerState.Status.PENDING&&!f.otherHall.override,"another hall and its plan remain untouched");
            String loaded=f.terminal.data.url;
            PanelGameTests.rejects(h,()->f.manager.loadShow(f.owner,future.id,revision),"replayed stale load rejected");
            PanelGameTests.rejects(h,()->f.manager.loadShow(f.owner,"missing",f.manager.state.revision),"unknown stable identity rejected");
            h.assertTrue(f.terminal.data.url.equals(loaded),"rejected load leaves current source alone");
            CompoundTag saved=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());ManagerTile restored=new ManagerTile(f.manager.getBlockPos(),f.manager.getBlockState());restored.loadWithComponents(saved,h.getLevel().registryAccess());
            h.assertTrue(restored.state.show(future.id).url.equals(source)&&restored.state.show(future.id).status==ManagerState.Status.PENDING&&restored.state.hall(f.hall.id).override,"saved plan identity/source and manual takeover persist");h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void schedule_actions_recheck_owner_revision_and_active_impact(GameTestHelper h){
        Fixture f=fixture(h);var show=plan(f,f.hall,"Editable","water://editable",10);var guest=h.makeMockServerPlayerInLevel();guest.setPos(f.owner.position());
        InfoTile lobby=PanelGameTests.place(h,f.owner,new BlockPos(2,1,2));lobby.configure(f.owner,PanelGroup.of(lobby).signature(),PanelSettings.DEFAULT,PanelScope.LOBBY);lobby.bind(f.owner,f.manager,"");
        long revision=f.manager.state.revision;String before=f.terminal.data.url;
        PanelGameTests.rejects(h,()->f.manager.loadShow(guest,show.id,revision),"guest cannot load saved private source");
        PanelGameTests.rejects(h,()->f.manager.deleteShow(guest,show.id,revision,true),"guest cannot delete a show");
        PanelGameTests.rejects(h,()->f.manager.deleteShow(f.owner,show.id,revision,false),"delete requires reviewed confirmation");
        CompoundTag edit=new CompoundTag();edit.putString("title","Edited");edit.putString("url","water://edited");edit.putString("time",f.manager.state.inputTime(show.start+120000));edit.putInt("minutes",2);
        PanelGameTests.rejects(h,()->f.manager.editShow(guest,show.id,revision,edit),"guest cannot edit saved show");
        f.manager.editShow(f.owner,show.id,revision,edit);
        h.assertTrue(show.title.equals("Edited")&&f.terminal.data.url.equals(before)&&!f.hall.override,"owner edit changes only pending plan");
        lobby.refreshNow();var publicRows=PanelNetwork.guestData(lobby).snapshot().rows();h.assertTrue(publicRows.size()==1&&publicRows.getFirst().id().equals(show.id)&&publicRows.getFirst().title().equals(show.title)&&publicRows.getFirst().start()==show.start,"lobby reflects edited title/time under the same stable show identity");
        h.assertFalse(PanelNetwork.JSON.toJson(PanelNetwork.guestData(lobby)).contains(show.url),"edited source remains private in lobby data");
        PanelGameTests.rejects(h,()->f.manager.deleteShow(f.owner,show.id,revision,true),"old confirmation cannot delete a changed record");
        show.status=ManagerState.Status.RUNNING;String active=StateCodec.encode(f.manager.state);
        boolean paused=f.terminal.data.paused,on=f.terminal.data.active;var clock=f.terminal.clock();boolean playing=clock!=null&&clock.playing();
        PanelGameTests.rejects(h,()->f.manager.editShow(f.owner,show.id,f.manager.state.revision,edit),"active edit rejected");
        PanelGameTests.rejects(h,()->f.manager.deleteShow(f.owner,show.id,f.manager.state.revision,true),"active deletion rejected");
        h.assertTrue(active.equals(StateCodec.encode(f.manager.state))&&f.terminal.data.url.equals(before)&&f.terminal.data.paused==paused&&f.terminal.data.active==on&&f.terminal.clock()==clock&&(clock==null||clock.playing()==playing),"active edit/delete rejection preserves full transport and plan state");
        PanelGameTests.rejects(h,()->f.manager.cancelShow(f.owner,show.id,f.manager.state.revision,false),"active cancellation requires confirmation");
        h.assertTrue(f.terminal.projectors().size()==1&&f.terminal.playbackAvailable(),"existing singleton theater is a supported WF playback configuration");
        f.manager.cancelShow(f.owner,show.id,f.manager.state.revision,true);
        var output=(CinemaProjectorTile)h.getLevel().getBlockEntity(f.terminal.projectors().keySet().iterator().next());
        h.assertTrue(!output.data.active&&output.terminal()==f.terminal,"singleton cancellation disables its output without clearing the binding");
        h.assertTrue(show.status==ManagerState.Status.CANCELLED&&f.terminal.data.paused&&f.hall.override,"confirmed active cancel stops and takes manual control");
        f.manager.deleteShow(f.owner,show.id,f.manager.state.revision,true);h.assertTrue(f.manager.state.shows.isEmpty(),"stopped cancelled record can be deleted");lobby.refreshNow();h.assertTrue(PanelNetwork.guestData(lobby).snapshot().rows().isEmpty(),"deleted plan is absent from linked lobby");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void schedule_sources_only_reach_authorized_management_snapshots(GameTestHelper h) throws Exception {
        Fixture f=fixture(h);var show=plan(f,f.hall,"Private","water://private-plan",10);
        var method=ManagerNetwork.class.getDeclaredMethod("publicJson",ManagerTile.class,ServerPlayer.class,boolean.class);method.setAccessible(true);
        String owner=(String)method.invoke(null,f.manager,f.owner,false);h.assertTrue(owner.contains(show.url),"authorized manager can review and reuse the stored source");
        String readOnly=(String)method.invoke(null,f.manager,f.owner,true);h.assertFalse(readOnly.contains(show.url),"read-only management copy redacts media");
        var guest=h.makeMockServerPlayerInLevel();guest.setPos(f.owner.position());String denied=(String)method.invoke(null,f.manager,guest,false);h.assertFalse(denied.contains(show.url),"forged non-read-only guest request still redacts media");
        f.manager.owner=UUID.randomUUID();String revoked=(String)method.invoke(null,f.manager,f.owner,false);h.assertFalse(revoked.contains(show.url),"revoked ownership removes source access");
        h.assertFalse(f.manager.getUpdateTag(h.getLevel().registryAccess()).toString().contains(show.url),"public block update never exposes schedule sources");h.succeed();
    }
}
