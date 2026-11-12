package local.cinemamanager.qa;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import local.cinemamanager.*;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.library.CatalogStorage;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import local.cinemamanager.panels.assets.AssetPanelLease;
import local.cinemamanager.theater.CinemaProgramme;
import local.cinemamanager.media.CinemaSources;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Loaded manager/TMS/panel, source leases, immutable film snapshots and public NBT.
 * Source evidence and viewer Ready are synthetic GameTest inputs. These fixtures do not
 * fetch or decode media, render a poster, authenticate a network viewer, or establish audio.
 */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ActualProgrammePosterGameTests {
    private static final String POSTER="resource:mycinema:textures/block/info.png";
    private static final String NEXT_POSTER="resource:minecraft:textures/block/stone.png";
    private static final String PRIVATE_SOURCE_PREFIX="water://private-actual-source-";
    private record Fixture(FilmPlaybackGameTests.Fixture playback,FilmCatalog.Film film,InfoTile panel) {}

    private static Fixture fixture(GameTestHelper h,long duration,int clips)throws Exception{
        String source=PRIVATE_SOURCE_PREFIX+UUID.randomUUID();
        h.assertTrue(CinemaSources.validSource(new CinemaSources.SourceRef(source,0)),"synthetic source uses a valid normalized water URI before owner authorization");
        var f=new FilmPlaybackGameTests.Fixture(h,true,duration,clips,source);
        var film=edit(f,f.film,"Actual programme",POSTER);
        InfoTile panel=PanelGameTests.place(h,f.actor,new BlockPos(2,1,2));panel.bind(f.actor,f.manager,f.hall.id);
        panel.panel=new PanelSettings(PanelSettings.Layout.POSTER_LEFT,"","","","PRIVATE_EDITOR_NEXT","PRIVATE_EDITOR_FOOTER","",0xffffff,0,0xabcdef,100,PanelSettings.ALL);
        f.authorize();f.ready(true);return new Fixture(f,film,panel);
    }
    private static FilmCatalog.Film edit(FilmPlaybackGameTests.Fixture f,FilmCatalog.Film film,String title,String poster){
        return f.manager.library.update(f.actor,film.id(),film.revision(),new FilmCatalog.Draft(title,poster,film.segments().stream().map(FilmCatalog.Segment::sourceKey).toList()));
    }
    private static void load(Fixture f){var p=f.playback;p.manager.playback.load(p.actor,p.hall.id,f.film.id(),f.film.revision(),true);}
    private static ManagerState.Show show(Fixture f,FilmCatalog.Snapshot snapshot,long start,ManagerState.Status status){
        var p=f.playback;var show=new ManagerState.Show();show.hallId=p.hall.id;show.start=start;show.end=start+snapshot.totalDurationMs();show.status=status;
        show.title=snapshot.film().title();show.url=snapshot.film().segments().getFirst().sourceKey();show.filmId=snapshot.film().id();
        show.filmRevision=snapshot.film().revision();show.filmSnapshot=snapshot.id();show.filmDurationMs=snapshot.totalDurationMs();p.manager.state.shows.add(show);return show;
    }
    private static PanelNetwork.GuestData published(GameTestHelper h,Fixture f,String poster){
        var panel=f.panel;panel.refreshNow();var guest=PanelNetwork.guestData(panel);var tag=panel.getUpdateTag(h.getLevel().registryAccess());
        h.assertTrue(panel.approvedPoster().equals(poster)&&panel.publishedPoster.equals(poster)&&guest.settings().poster().equals(poster)
                &&tag.getString("published_poster").equals(poster)&&PanelSettings.read(tag.getCompound("panel_settings")).poster().equals(poster),"physical panel, public settings, guest detail and block NBT agree on the approved poster");
        var wire=PublicPanelSnapshot.read(tag.getCompound("guest_snapshot"));
        h.assertTrue(panel.publicSnapshot.rows().equals(guest.snapshot().rows())&&wire.rows().equals(guest.snapshot().rows())
                &&wire.timezone().equals(guest.snapshot().timezone()),"physical panel and guest detail agree on the actual public programme rows");
        h.assertTrue(poster.isEmpty()?panel.publishedPosterToken.isEmpty()&&tag.getString("poster_publication").isEmpty():
                PosterPublicationState.validToken(panel.publishedPosterToken)&&panel.publishedPosterToken.equals(tag.getString("poster_publication")),"opaque poster publication follows the same decision");
        return guest;
    }
    private static void withdrawn(GameTestHelper h,Fixture f,String why){
        var p=f.playback;h.assertTrue(p.manager.playback.publicProgramme(p.hall.id)==null,why);
        h.assertTrue(published(h,f,"").snapshot().rows().isEmpty(),"no stale actual programme survives in guest or physical publication: "+why);
    }
    private static void privatePayload(GameTestHelper h,Fixture f){
        var p=f.playback;var selected=p.manager.playback.publicProgramme(p.hall.id);h.assertTrue(selected!=null,"privacy check has an actual leased programme");
        var ownerView=PlaylistTransitionGameTests.view(p);String seek=p.view().seekToken;
        String publicPayload=PanelNetwork.JSON.toJson(PanelNetwork.guestData(f.panel))+f.panel.getUpdateTag(h.getLevel().registryAccess());
        for(String secret:List.of(selected.snapshot().id(),selected.snapshot().film().id(),p.actor.getUUID().toString(),p.actor.getGameProfile().getName(),
                p.manager.identity.toString(),p.terminal.identity().toString(),p.terminal.mediaEpoch().toString(),ownerView.run().toString(),
                PRIVATE_SOURCE_PREFIX,"PRIVATE_EDITOR_NEXT","PRIVATE_EDITOR_FOOTER","filmSnapshot","seekToken","operationRevision","boundSource"))
            h.assertFalse(publicPayload.contains(secret),"public poster/row payload excludes "+secret);
        if(!seek.isBlank())h.assertFalse(publicPayload.contains(seek),"owner seek ticket never becomes the manual display identity");
        h.assertFalse(selected.publicId().equals(ownerView.run().toString())||selected.publicId().equals(seek),"public programme identity is independent of operation authority");
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_manual_programme_wins_over_future_and_missed_plans_and_keeps_immutable_poster(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,2);var p=f.playback;load(f);
        var nextFilm=edit(p,f.film,"Upcoming programme",NEXT_POSTER);var next=p.manager.library.catalog().snapshot(nextFilm.id(),nextFilm.revision());
        var future=show(f,next,System.currentTimeMillis()+180_000,ManagerState.Status.PENDING);
        show(f,next,System.currentTimeMillis()-60_000,ManagerState.Status.MISSED);
        h.runAfterDelay(16,()->{
            String before=StateCodec.encode(p.manager.state);UUID epoch=p.terminal.mediaEpoch();
            var first=published(h,f,POSTER);var row=first.snapshot().rows().getFirst();
            h.assertTrue(first.snapshot().rows().size()==1&&row.title().equals("Actual programme")&&!row.id().equals(future.id),"actual manual film wins over future and missed schedule entries");
            h.assertTrue(!row.scheduled()&&row.start()==0&&row.end()==0&&row.durationMs()==12_000,"manual film exposes no invented wall-clock slot and excludes the transition gap from media duration");
            h.assertTrue(StateCodec.encode(p.manager.state).equals(before)&&epoch.equals(p.terminal.mediaEpoch())&&p.terminal.clock().paused(),"public selection is read-only and cannot start media or change schedule state");
            h.assertTrue(f.panel.panel.poster().isEmpty()&&first.settings().layout()==PanelSettings.Layout.POSTER_LEFT,"automatic inheritance preserves the blank override and chosen layout");
            privatePayload(h,f);
            PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.PLAY);
            h.assertTrue(published(h,f,POSTER).snapshot().rows().getFirst().id().equals(row.id()),"Play keeps the same public programme identity");
            PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.PAUSE);
            for(String zone:List.of("","Mars/Phobos")){
                p.manager.state.timezone=zone;var noZone=published(h,f,POSTER);
                h.assertTrue(noZone.snapshot().timezone().isEmpty()&&!noZone.snapshot().rows().getFirst().scheduled(),"missing or invalid timezone cannot hide an actual manual film or invent a schedule");
            }
            p.manager.state.timezone="UTC";
            h.assertTrue(p.manager.playback.stop(p.hall.id,p.actor)==null,"manual programme stops through its verified lease");
            h.assertTrue(p.manager.playback.publicProgramme(p.hall.id)==null,"stopped manual history is not an actual programme");
            var fallback=published(h,f,NEXT_POSTER).snapshot().rows();
            h.assertTrue(fallback.size()==1&&fallback.getFirst().id().equals(future.id)&&fallback.getFirst().scheduled(),"stopped actual programme yields to the future plan while missed plans remain absent");
            p.manager.state.shows.clear();withdrawn(h,f,"stopped history without an upcoming plan");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_scheduled_identity_beats_stale_running_rows_and_cancel_withdraws_it(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,2);var p=f.playback;long now=System.currentTimeMillis();
        var snapshot=p.manager.library.catalog().snapshot(f.film.id(),f.film.revision());
        var actual=show(f,snapshot,now-30_000,ManagerState.Status.PREPARING);
        var otherFilm=edit(p,f.film,"Stale stored programme",NEXT_POSTER);var other=p.manager.library.catalog().snapshot(otherFilm.id(),otherFilm.revision());
        var stale=show(f,other,now-60_000,ManagerState.Status.RUNNING);
        var stalePreparing=show(f,other,now-45_000,ManagerState.Status.PREPARING);
        var future=show(f,other,now+180_000,ManagerState.Status.PENDING);
        h.assertTrue(p.manager.playback.startScheduled(p.hall,actual)==null,"actual scheduled programme starts from its own immutable snapshot");
        h.runAfterDelay(16,()->{
            h.assertTrue(p.manager.playback.transport(p.hall.id,true,p.actor)==null,"scheduled fixture pauses without converting its claim to manual");
            var selected=p.manager.playback.publicProgramme(p.hall.id);var row=published(h,f,POSTER).snapshot().rows().getFirst();
            h.assertTrue(selected!=null&&selected.scheduled()==actual&&selected.publicId().equals(actual.id)&&row.id().equals(actual.id),"actual programme identity beats an earlier stale RUNNING schedule row");
            h.assertTrue(row.scheduled()&&row.start()==actual.start&&row.end()==actual.end&&actual.end<System.currentTimeMillis(),"delayed actual programme keeps its true nominal schedule after nominal end");
            privatePayload(h,f);
            actual.hallId="other-hall";h.assertTrue(p.manager.playback.publicProgramme(p.hall.id)==null,"scheduled row moved to another hall cannot authorize the original run");actual.hallId=p.hall.id;
            String exact=actual.filmSnapshot;actual.filmSnapshot=other.id();h.assertTrue(p.manager.playback.publicProgramme(p.hall.id)==null,"different immutable snapshot cannot impersonate the scheduled run");actual.filmSnapshot=exact;
            actual.status=ManagerState.Status.CANCELLED;h.assertTrue(p.manager.playback.publicProgramme(p.hall.id)==null,"cancelled plan is immediately excluded from actual selection");
            actual.status=ManagerState.Status.RUNNING;
            h.assertTrue(p.manager.playback.stopScheduled(p.hall.id,actual.id)==null&&actual.status==ManagerState.Status.CANCELLED,"verified scheduled Stop retires its exact show as cancelled");
            var fallback=published(h,f,NEXT_POSTER).snapshot().rows();
            h.assertTrue(stale.status==ManagerState.Status.RUNNING&&stalePreparing.status==ManagerState.Status.PREPARING,"read-only publication leaves stale schedule history unchanged");
            h.assertTrue(fallback.size()==1&&fallback.getFirst().id().equals(future.id),"cancelled actual programme yields to only the eligible future plan, never stale running or preparing history");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void scheduled_poster_survives_operational_scenes_and_play_pause_until_explicit_stop(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,2);var p=f.playback;
        var snapshot=p.manager.library.catalog().snapshot(f.film.id(),f.film.revision());
        var actual=show(f,snapshot,System.currentTimeMillis()-30_000,ManagerState.Status.PREPARING);
        h.assertTrue(p.manager.playback.startScheduled(p.hall,actual)==null,"fixture starts its exact scheduled programme");
        h.runAfterDelay(16,()->{
            h.assertTrue(p.manager.playback.transport(p.hall.id,true,p.actor)==null,"fixture pauses its current lease");
            var initial=published(h,f,POSTER);String token=f.panel.publishedPosterToken;
            UUID epoch=p.terminal.mediaEpoch();var run=PlaylistTransitionGameTests.view(p).run();
            for(var scene:List.of(ManagerState.Scene.OPEN,ManagerState.Scene.ADMISSION_OPEN,ManagerState.Scene.ADMISSION_CLOSE,
                    ManagerState.Scene.LIGHTS_OFF,ManagerState.Scene.LIGHTS_HALF,ManagerState.Scene.LIGHTS_ON)){
                p.manager.scene(p.actor,p.hall.id,scene);
                var selected=p.manager.playback.publicProgramme(p.hall.id);
                h.assertTrue(selected!=null&&selected.scheduled()==actual&&actual.status==ManagerState.Status.RUNNING,"operational scene preserves exact active scheduled identity: "+scene);
                h.assertTrue(published(h,f,POSTER).snapshot().rows().equals(initial.snapshot().rows())&&token.equals(f.panel.publishedPosterToken),"operational scene keeps poster row and publication token: "+scene);
                h.assertTrue(epoch.equals(p.terminal.mediaEpoch())&&run.equals(PlaylistTransitionGameTests.view(p).run()),"operational scene never reloads or replaces the media lease: "+scene);
            }
            p.manager.lighting(p.actor,p.hall.id,ManagerState.LightPreset.ENTRY);
            h.assertTrue(actual.status==ManagerState.Status.RUNNING&&p.manager.playback.publicProgramme(p.hall.id)!=null,"lighting preset does not retire active programme");
            published(h,f,POSTER);h.assertTrue(token.equals(f.panel.publishedPosterToken),"lighting preset does not invalidate poster transfer");
            for(var action:List.of(CinemaProgramme.Action.PLAY,CinemaProgramme.Action.PAUSE,CinemaProgramme.Action.PLAY)){
                PlaylistTransitionGameTests.control(p,action);
                published(h,f,POSTER);h.assertTrue(token.equals(f.panel.publishedPosterToken)&&actual.status==ManagerState.Status.RUNNING,"same-programme play/pause keeps its poster: "+action);
            }
            h.assertTrue(p.manager.playback.stopScheduled(p.hall.id,actual.id)==null,"explicit stop ends the selected screening");
            withdrawn(h,f,"explicit stopped screening clears its poster when no next screening exists");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_programme_cross_hall_manager_owner_and_terminal_owner_fail_closed(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,1);var p=f.playback;load(f);
        h.runAfterDelay(16,()->{
            published(h,f,POSTER);String originalToken=f.panel.publishedPosterToken;
            var foreign=new ManagerState.Hall();foreign.name="Other hall";foreign.terminalPos=p.hall.terminalPos;foreign.terminalIdentity=p.hall.terminalIdentity;p.manager.state.halls.add(foreign);
            h.assertTrue(p.manager.playback.publicProgramme(foreign.id)==null,"even an aliased terminal cannot borrow another hall's current programme");
            f.panel.hallId=foreign.id;h.assertTrue(published(h,f,"").snapshot().rows().isEmpty(),"another hall's panel never inherits the active hall's poster or title");
            f.panel.hallId=p.hall.id;published(h,f,POSTER);
            UUID owner=p.manager.owner;p.manager.owner=UUID.randomUUID();withdrawn(h,f,"manager owner differs from the runtime programme owner");
            p.manager.owner=owner;published(h,f,POSTER);
            h.assertFalse(originalToken.equals(f.panel.publishedPosterToken),"restoring owner cannot revive an old poster callback");
            p.terminal.owner(UUID.randomUUID());withdrawn(h,f,"mixed manager and terminal ownership revokes the source grant");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_programme_grant_revocation_retires_poster_transfer_immediately(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,1);var p=f.playback;load(f);
        var futureFilm=edit(p,f.film,"Future after revoked programme",NEXT_POSTER);
        var future=show(f,p.manager.library.catalog().snapshot(futureFilm.id(),futureFilm.revision()),System.currentTimeMillis()+180_000,ManagerState.Status.PENDING);
        h.runAfterDelay(16,()->{
            published(h,f,POSTER);var lease=new AssetPanelLease(f.panel,PanelGroup.of(f.panel).signature());
            h.assertTrue(lease.downloadCurrent(p.actor,POSTER),"current exact programme permits its approved poster transfer");
            p.terminal.revokeAutomation();withdrawn(h,f,"automation grant revoked before the next manager tick");
            h.assertFalse(lease.downloadCurrent(p.actor,POSTER),"in-flight transfer cannot outlive the programme authorization");
            h.runAfterDelay(12,()->{
                h.assertFalse(p.manager.playback.currentProgrammePending(p.hall.id),"manager retires the revoked current run before using schedule fallback");
                var rows=published(h,f,NEXT_POSTER).snapshot().rows();
                h.assertTrue(rows.size()==1&&rows.getFirst().id().equals(future.id)&&rows.getFirst().scheduled(),"future poster and title appear together only after the rejected current run retires");h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_empty_saved_poster_does_not_borrow_a_later_library_revision(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,1);var p=f.playback;
        var empty=edit(p,f.film,"Actual film with saved empty poster","");
        p.manager.playback.load(p.actor,p.hall.id,empty.id(),empty.revision(),true);
        edit(p,empty,"Later edited library film",NEXT_POSTER);
        h.runAfterDelay(16,()->{
            var selected=p.manager.playback.publicProgramme(p.hall.id);var rows=published(h,f,"").snapshot().rows();
            h.assertTrue(selected!=null&&selected.snapshot().film().poster().isEmpty()&&rows.size()==1&&rows.getFirst().title().equals(empty.title()),"saved manual programme keeps its exact title and empty poster despite a newer library revision");
            h.assertTrue(f.panel.diagnostics().contains("mycinema.panel.poster_saved_empty"),"owner receives an actionable saved-empty-snapshot reason");
            String guest=PanelNetwork.JSON.toJson(PanelNetwork.guestData(f.panel))+f.panel.getUpdateTag(h.getLevel().registryAccess());
            h.assertFalse(guest.contains("poster_saved_empty")||guest.contains("Later edited library film")||guest.contains(NEXT_POSTER),"private diagnostic and later revision never leak into guest publication");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_empty_poster_late_add_is_display_only_and_revokes_transfer(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,2);var p=f.playback;
        var empty=edit(p,f.film,"Poster-only update","");
        p.manager.playback.load(p.actor,p.hall.id,empty.id(),empty.revision(),true);
        h.runAfterDelay(16,()->{
            var saved=p.manager.playback.publicProgramme(p.hall.id).snapshot();
            UUID epoch=p.terminal.mediaEpoch();
            published(h,f,"");var added=edit(p,empty,empty.title(),NEXT_POSTER);
            String before=StateCodec.encode(p.manager.state);
            var row=published(h,f,NEXT_POSTER).snapshot().rows().getFirst();
            h.assertTrue(row.title().equals(empty.title())&&p.manager.library.catalog().snapshot(saved.id()).equals(saved),"late poster inherits only when exact saved programme content still matches");
            h.assertTrue(saved.film().poster().isEmpty()&&p.manager.playback.publicProgramme(p.hall.id).snapshot().equals(saved)
                    &&before.equals(StateCodec.encode(p.manager.state))&&epoch.equals(p.terminal.mediaEpoch()),"poster publication never rewrites saved media, programme, schedule or terminal epoch");
            h.assertTrue(p.manager.library.posterProblem(saved).isEmpty()&&p.manager.library.posterSnapshotTarget(saved,NEXT_POSTER,NEXT_POSTER),"diagnostics and target checks use the selected current poster");
            var lease=new AssetPanelLease(f.panel,PanelGroup.of(f.panel).signature());String token=f.panel.publishedPosterToken;
            h.assertTrue(lease.downloadCurrent(p.actor,NEXT_POSTER),"late-added approved poster has a live transfer lease");
            var foreignAuthor=new FilmCatalog.Publication(UUID.randomUUID().toString(),"Other poster author",p.manager.identity.toString(),p.manager.owner.toString(),NEXT_POSTER);
            var sameUri=p.manager.library.catalog().update(added.id(),added.revision(),new FilmCatalog.Draft(added.title(),NEXT_POSTER,added.segments().stream().map(FilmCatalog.Segment::sourceKey).toList()),foreignAuthor);
            published(h,f,NEXT_POSTER);
            h.assertFalse(token.equals(f.panel.publishedPosterToken)||lease.downloadCurrent(p.actor,NEXT_POSTER),"same URI with a different current publication revokes old transfer authority");
            var denied=new FilmCatalog.Publication(p.actor.getUUID().toString(),p.actor.getGameProfile().getName(),UUID.randomUUID().toString(),p.manager.owner.toString(),NEXT_POSTER);
            var activeLease=new AssetPanelLease(f.panel,PanelGroup.of(f.panel).signature());
            var invalid=p.manager.library.catalog().update(sameUri.id(),sameUri.revision(),new FilmCatalog.Draft(sameUri.title(),NEXT_POSTER,sameUri.segments().stream().map(FilmCatalog.Segment::sourceKey).toList()),denied);
            published(h,f,"");h.assertFalse(activeLease.downloadCurrent(p.actor,NEXT_POSTER)||p.manager.library.posterSnapshotTarget(saved,NEXT_POSTER,NEXT_POSTER),"current publication controller must be authorized on every transfer and target check");
            var restored=edit(p,invalid,empty.title(),NEXT_POSTER);published(h,f,NEXT_POSTER);
            var renamed=edit(p,restored,"Changed programme title",NEXT_POSTER);published(h,f,"");
            restored=edit(p,renamed,empty.title(),NEXT_POSTER);published(h,f,NEXT_POSTER);
            var repeated=restored.segments().stream().map(FilmCatalog.Segment::sourceKey).toList();
            var shortened=p.manager.library.update(p.actor,restored.id(),restored.revision(),new FilmCatalog.Draft(empty.title(),NEXT_POSTER,List.of(repeated.getFirst())));
            published(h,f,"");h.assertFalse(p.manager.library.posterSnapshotTarget(saved,NEXT_POSTER,NEXT_POSTER),"removing one repeated source occurrence cannot borrow the poster");
            restored=p.manager.library.update(p.actor,shortened.id(),shortened.revision(),new FilmCatalog.Draft(empty.title(),NEXT_POSTER,repeated));published(h,f,NEXT_POSTER);
            var observation=restored.segments().getFirst().observation();
            var metadataChanged=p.manager.library.catalog().observeTrusted(restored.id(),restored.revision(),0,repeated.getFirst(),false,observation.durationMs()+1,observation.provenance());
            published(h,f,"");
            restored=p.manager.library.catalog().observeTrusted(metadataChanged.id(),metadataChanged.revision(),0,repeated.getFirst(),false,observation.durationMs(),observation.provenance());published(h,f,NEXT_POSTER);
            var deleteLease=new AssetPanelLease(f.panel,PanelGroup.of(f.panel).signature());
            p.manager.library.delete(p.actor,restored.id(),restored.revision(),true);published(h,f,"");
            h.assertFalse(deleteLease.downloadCurrent(p.actor,NEXT_POSTER),"deleted current catalog entry immediately revokes fallback transfer");
            h.assertTrue(p.manager.playback.publicProgramme(p.hall.id).snapshot().equals(saved),"deleting fallback metadata cannot rewrite or retire valid saved media");
            privatePayload(h,f);h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_saved_local_poster_accepts_only_authorized_uploaded_asset(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,1);var p=f.playback;
        String localPoster="file:/private-saved-poster.png";
        var localFilm=p.manager.library.catalog().update(f.film.id(),f.film.revision(),new FilmCatalog.Draft(f.film.title(),localPoster,f.film.segments().stream().map(FilmCatalog.Segment::sourceKey).toList()),
                new FilmCatalog.Publication(p.actor.getUUID().toString(),p.actor.getGameProfile().getName(),p.manager.identity.toString(),p.manager.owner.toString(),localPoster));
        var saved=p.manager.library.catalog().snapshot(localFilm.id(),localFilm.revision());
        var planned=show(f,saved,System.currentTimeMillis()-1000,ManagerState.Status.PREPARING);
        h.assertTrue(p.manager.playback.startScheduled(p.hall,planned)==null,"historical local poster does not change immutable programme runtime validation");
        // Seed an actual validated asset in the server's own store. This is a test fixture,
        // not a simulated network upload or proof that an authenticated client rendered it.
        var runtimeMethod=local.cinemamanager.panels.assets.PanelAssets.class.getDeclaredMethod("runtime",net.minecraft.server.MinecraftServer.class);runtimeMethod.setAccessible(true);
        Object runtime=runtimeMethod.invoke(null,h.getLevel().getServer());var storeField=runtime.getClass().getDeclaredField("store");storeField.setAccessible(true);
        var store=(local.cinemamanager.panels.assets.AssetStore)storeField.get(runtime);
        var png=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);png.setRGB(0,0,0xff336699);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(png,"png",bytes);byte[] data=bytes.toByteArray();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        var upload=store.begin(data.length,hash,1024*1024,64*1024*1024);upload.append(0,data);
        String asset=store.commit(upload,upload.validate(1024*1024),p.actor.getUUID(),64*1024*1024);
        var added=edit(p,localFilm,localFilm.title(),asset);
        h.runAfterDelay(16,()->{
            published(h,f,asset);var lease=new AssetPanelLease(f.panel,PanelGroup.of(f.panel).signature());
            h.assertTrue(lease.downloadCurrent(p.actor,asset)&&saved.film().poster().equals(localPoster)
                    &&p.manager.playback.publicProgramme(p.hall.id).snapshot().equals(saved),"authorized uploaded asset supplies display only while old local snapshot stays intact");
            var denied=new FilmCatalog.Publication(UUID.randomUUID().toString(),"Unproven asset author",p.manager.identity.toString(),p.manager.owner.toString(),asset);
            p.manager.library.catalog().update(added.id(),added.revision(),new FilmCatalog.Draft(added.title(),asset,added.segments().stream().map(FilmCatalog.Segment::sourceKey).toList()),denied);
            published(h,f,"");h.assertFalse(lease.downloadCurrent(p.actor,asset)||p.manager.library.posterSnapshotTarget(saved,asset,null),"asset hash knowledge cannot authorize fallback publication for another author");
            String payload=PanelNetwork.JSON.toJson(PanelNetwork.guestData(f.panel))+f.panel.getUpdateTag(h.getLevel().registryAccess());
            h.assertFalse(payload.contains(localPoster)||payload.contains("Unproven asset author"),"private local path and author stay out of public payload");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void actual_programme_exact_epoch_and_controller_are_required(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,1);var p=f.playback;load(f);
        h.runAfterDelay(16,()->{
            published(h,f,POSTER);UUID epoch=p.terminal.mediaEpoch();
            String source=p.terminal.data.getUrl();var grant=p.terminal.automationGrant();
            // The supported disk load rotates the terminal epoch while preserving source,
            // owner, grant and controller. Do not restore the manager's old runtime lease.
            // Reflection over DisplayTile fields resolves client-only types on a server.
            var terminalSave=p.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
            p.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());
            h.assertTrue(!epoch.equals(p.terminal.mediaEpoch())&&source.equals(p.terminal.data.getUrl())&&p.terminal.ownedBy(p.actor.getUUID())
                    &&grant.equals(p.terminal.automationGrant()),"terminal disk reload rotates only the tested session authority while keeping source, owner and exact grant");
            h.assertTrue(CinemaSources.controlledBy(p.terminal,new CinemaSources.ControllerRef(p.manager.getBlockPos(),p.manager.identity)),"epoch test still has the exact source/controller lease");
            withdrawn(h,f,"same source and controller with a different media epoch");
            // Re-establish a verified run through the real owner load path, then isolate
            // controller revocation without changing that new run's epoch.
            load(f);
            h.runAfterDelay(16,()->{
                published(h,f,POSTER);UUID currentEpoch=p.terminal.mediaEpoch();
                p.terminal.cancelLibraryControl();
                h.assertTrue(currentEpoch.equals(p.terminal.mediaEpoch()),"controller-only revocation leaves the media epoch unchanged");
                withdrawn(h,f,"matching epoch without the source controller lease");h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=1_000_000)
    public static void actual_programme_advance_and_cold_reload_preserve_immutable_public_film(GameTestHelper h)throws Exception{
        var f=fixture(h,6000,2);var p=f.playback;load(f);
        h.runAfterDelay(16,()->{
            var initial=p.manager.playback.publicProgramme(p.hall.id);h.assertTrue(initial!=null,"manual programme owns its first prepared occurrence");
            published(h,f,POSTER);String token=f.panel.publishedPosterToken;UUID epoch=p.terminal.mediaEpoch();
            edit(p,f.film,"Edited library after load",NEXT_POSTER);
            PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.CHOOSE,1);
            h.assertTrue(published(h,f,POSTER).snapshot().rows().getFirst().id().equals(initial.publicId()),"transition gap retains the actual immutable programme rather than a future or edited film");
            PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.PLAY);
            PlaylistTransitionGameTests.await(h,System.nanoTime()+20_000_000_000L,
                    ()->PlaylistTransitionGameTests.view(p).currentIndex()==1&&PlaylistTransitionGameTests.view(p).status().equals("PLAYING"),()->{
                PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.PAUSE);
                var advanced=p.manager.playback.publicProgramme(p.hall.id);var row=published(h,f,POSTER).snapshot().rows().getFirst();
                h.assertTrue(advanced!=null&&advanced.publicId().equals(initial.publicId())&&advanced.snapshot().equals(initial.snapshot())
                        &&row.title().equals("Actual programme")&&!epoch.equals(p.terminal.mediaEpoch()),"new clip epoch retains the same film snapshot, public title and display identity");
                h.assertTrue(token.equals(f.panel.publishedPosterToken),"advancing within the same immutable programme does not invalidate its approved poster");
                var managerSave=p.manager.saveWithoutMetadata(h.getLevel().registryAccess());var terminalSave=p.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
                for(String invalidStatus:List.of("missing","null","UNKNOWN_STATUS")){
                    var malformed=managerSave.copy();
                    var programmes=JsonParser.parseString(CatalogStorage.read(malformed,"film_programmes")).getAsJsonArray();
                    var savedState=programmes.get(0).getAsJsonObject().getAsJsonObject("state");
                    if(invalidStatus.equals("missing"))savedState.remove("status");
                    else if(invalidStatus.equals("null"))savedState.add("status",JsonNull.INSTANCE);
                    else savedState.addProperty("status",invalidStatus);
                    malformed.put("film_programmes",CatalogStorage.write(programmes.toString()));
                    p.manager.playback.load(malformed);
                    h.assertTrue(p.manager.playback.currentProgrammePending(p.hall.id),"unvalidated malformed saved status withholds fallback until restore rejects it: "+invalidStatus);
                    withdrawn(h,f,"malformed saved status before the first restore tick: "+invalidStatus);
                }
                p.ready(false);p.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());p.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
                withdrawn(h,f,"saved playback history is not current before its source lease is reverified");
                p.ready(true);
                h.runAfterDelay(16,()->{
                    var restored=p.manager.playback.publicProgramme(p.hall.id);var restoredRow=published(h,f,POSTER).snapshot().rows().getFirst();
                    h.assertTrue(restored!=null&&restored.snapshot().equals(initial.snapshot())&&restoredRow.title().equals("Actual programme")
                            &&!restoredRow.scheduled()&&restoredRow.start()==0&&restoredRow.end()==0,"verified cold restoration keeps the original manual film and no invented schedule");
                    h.assertTrue(!token.equals(f.panel.publishedPosterToken),"cold restoration never revives a pre-reload publication callback");
                    h.assertTrue(PlaylistTransitionGameTests.view(p).currentIndex()==1&&p.terminal.clock().paused(),"publication cannot restart or select a different restored clip");privatePayload(h,f);h.succeed();
                });
            },"second clip did not finish the real three-second transition gap");
        });
    }

    @GameTest(template="empty",timeoutTicks=1_000_000)
    public static void completed_current_screening_retains_poster_until_explicit_stop_and_rechecks_authority(GameTestHelper h)throws Exception{
        var f=fixture(h,300,1);var p=f.playback;load(f);
        h.runAfterDelay(16,()->{
            var initial=published(h,f,POSTER);String token=f.panel.publishedPosterToken;
            PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.PLAY);
            PlaylistTransitionGameTests.await(h,System.nanoTime()+10_000_000_000L,()->!p.manager.playback.active(p.hall.id),()->{
                h.assertTrue(PlaylistTransitionGameTests.view(p).status().equals("COMPLETED"),"natural Ended produces completed programme history");
                UUID epoch=p.terminal.mediaEpoch();var clock=p.terminal.clock();
                h.assertTrue(p.manager.playback.publicProgramme(p.hall.id)==null&&p.manager.playback.publicDisplayProgramme(p.hall.id)!=null,"retained display does not pretend playback is active");
                h.assertTrue(published(h,f,POSTER).snapshot().rows().equals(initial.snapshot().rows())&&token.equals(f.panel.publishedPosterToken),"natural completion keeps the same screening row and poster token");
                for(var scene:List.of(ManagerState.Scene.OPEN,ManagerState.Scene.ADMISSION_OPEN,ManagerState.Scene.LIGHTS_HALF)){
                    p.manager.scene(p.actor,p.hall.id,scene);published(h,f,POSTER);
                    h.assertTrue(epoch.equals(p.terminal.mediaEpoch())&&clock==p.terminal.clock()&&!p.manager.playback.active(p.hall.id),"completed display operation never restarts transport: "+scene);
                }
                UUID owner=p.manager.owner;p.manager.owner=UUID.randomUUID();
                h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null,"changed owner immediately denies retained completed display");published(h,f,"");p.manager.owner=owner;published(h,f,POSTER);
                String source=p.terminal.data.url;p.terminal.data.url="water://different-completed-source";
                h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null,"changed source cannot borrow completed display scope");published(h,f,"");p.terminal.data.url=source;published(h,f,POSTER);
                PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.STOP);
                h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null,"explicit Stop dismisses completed display while keeping history");
                withdrawn(h,f,"explicitly dismissed completed screening has no future fallback");
                h.assertTrue(PlaylistTransitionGameTests.view(p).status().equals("COMPLETED")&&epoch.equals(p.terminal.mediaEpoch()),"display dismissal preserves completed history and media identity");
                var saved=p.manager.saveWithoutMetadata(h.getLevel().registryAccess());p.manager.playback.load(saved);
                h.runAfterDelay(12,()->{h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null,"dismissed display flag persists across reload");published(h,f,"");h.succeed();});
            },"single short programme did not naturally complete");
        });
    }

    @GameTest(template="empty",timeoutTicks=1_000_000)
    public static void completed_scheduled_display_revalidates_cold_restore_and_close_releases_next_poster(GameTestHelper h)throws Exception{
        var f=fixture(h,300,1);var p=f.playback;
        var snapshot=p.manager.library.catalog().snapshot(f.film.id(),f.film.revision());
        var actual=show(f,snapshot,System.currentTimeMillis()-5_000,ManagerState.Status.PREPARING);
        var nextFilm=edit(p,f.film,"Next screening",NEXT_POSTER);var next=p.manager.library.catalog().snapshot(nextFilm.id(),nextFilm.revision());
        var future=show(f,next,System.currentTimeMillis()+180_000,ManagerState.Status.PENDING);
        h.assertTrue(p.manager.playback.startScheduled(p.hall,actual)==null,"scheduled screening starts with exact retained snapshot");
        PlaylistTransitionGameTests.await(h,System.nanoTime()+10_000_000_000L,()->!p.manager.playback.active(p.hall.id),()->{
            h.assertTrue(p.manager.state.show(actual.id).status==ManagerState.Status.COMPLETED,"scheduled screening naturally completed");
            h.assertTrue(published(h,f,POSTER).snapshot().rows().getFirst().id().equals(actual.id),"completed current screening outranks a future poster until dismissal");
            var managerSave=p.manager.saveWithoutMetadata(h.getLevel().registryAccess());var terminalSave=p.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
            p.ready(false);p.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());p.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
            UUID coldEpoch=p.terminal.mediaEpoch();
            h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null&&p.manager.playback.currentDisplayPending(p.hall.id),"cold saved screening waits for exact authority verification");published(h,f,"");
            h.runAfterDelay(12,()->{
                h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)!=null&&!p.manager.playback.active(p.hall.id),"verified retained display returns without activating completed playback");
                h.assertTrue(published(h,f,POSTER).snapshot().rows().getFirst().id().equals(actual.id)&&coldEpoch.equals(p.terminal.mediaEpoch()),"cold revalidation preserves exact screening without source commands");
                var legacy=managerSave.copy();var entries=JsonParser.parseString(CatalogStorage.read(legacy,"film_programmes")).getAsJsonArray();
                entries.get(0).getAsJsonObject().remove("displayRetained");legacy.put("film_programmes",CatalogStorage.write(entries.toString()));
                p.manager.playback.load(legacy);
                h.runAfterDelay(12,()->{
                    h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null,"old completed history without explicit retention is never promoted");
                    h.assertTrue(published(h,f,NEXT_POSTER).snapshot().rows().getFirst().id().equals(future.id),"legacy history does not hide an eligible future plan");
                    p.manager.playback.load(managerSave);
                    h.runAfterDelay(12,()->{
                        published(h,f,POSTER);p.manager.scene(p.actor,p.hall.id,ManagerState.Scene.CLOSE);
                        h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null,"explicit Close dismisses the verified completed screening");
                        h.assertTrue(published(h,f,NEXT_POSTER).snapshot().rows().getFirst().id().equals(future.id),"Close releases next-screening fallback");h.succeed();
                    });
                });
            });
        },"scheduled short screening did not naturally complete");
    }

    @GameTest(template="empty",timeoutTicks=1_000_000)
    public static void new_programme_replaces_completed_display_and_revoked_grant_withholds_it(GameTestHelper h)throws Exception{
        var f=fixture(h,300,1);var p=f.playback;load(f);
        h.runAfterDelay(16,()->{
            PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.PLAY);
            PlaylistTransitionGameTests.await(h,System.nanoTime()+10_000_000_000L,()->!p.manager.playback.active(p.hall.id),()->{
                var old=published(h,f,POSTER).snapshot().rows().getFirst();String token=f.panel.publishedPosterToken;
                var next=edit(p,f.film,"Replacement screening",NEXT_POSTER);
                p.manager.playback.load(p.actor,p.hall.id,next.id(),next.revision(),true);
                h.runAfterDelay(16,()->{
                    var replaced=published(h,f,NEXT_POSTER).snapshot().rows().getFirst();
                    h.assertTrue(!replaced.id().equals(old.id())&&!token.equals(f.panel.publishedPosterToken)&&replaced.title().equals("Replacement screening"),"new exact programme replaces completed row and publication identity");
                    PlaylistTransitionGameTests.control(p,CinemaProgramme.Action.PLAY);
                    PlaylistTransitionGameTests.await(h,System.nanoTime()+10_000_000_000L,()->!p.manager.playback.active(p.hall.id),()->{
                        published(h,f,NEXT_POSTER);p.terminal.revokeAutomation();
                        h.assertTrue(p.manager.playback.publicDisplayProgramme(p.hall.id)==null,"revoked live grant denies retained completion");published(h,f,"");
                        p.manager.playback.forget(p.hall.id);h.assertTrue(!p.manager.playback.currentDisplayPending(p.hall.id),"explicit forget removes retained display state");h.succeed();
                    },"replacement screening did not naturally complete");
                });
            },"initial screening did not naturally complete");
        });
    }
}
