package local.cinemamanager.qa;

import local.cinemamanager.*;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import local.cinemamanager.panels.assets.AssetPanelLease;
import local.cinemamanager.panels.assets.AssetReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.neoforged.neoforge.gametest.*;
import java.util.List;
import java.util.UUID;

/** Loaded-world poster authorization fixtures only. Synthetic duration facts never prepare or play media. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FilmGuestPosterGameTests {
    private static final String POSTER="resource:mycinema:textures/block/info.png";
    private static final String OTHER_POSTER="resource:minecraft:textures/block/stone.png";
    private static final String MEDIA="file:/PRIVATE_FILM_MEDIA_SENTINEL.mkv";
    private record Fixture(ServerPlayer owner,ManagerTile manager,ManagerState.Hall hall,InfoTile panel){}
    private static Fixture fixture(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"isolated opt-in fixtures only");
        ServerPlayer owner=PanelGameTests.actor(h);h.setBlock(new BlockPos(4,1,2),CinemaManager.MANAGER.get());
        ManagerTile manager=h.getBlockEntity(new BlockPos(4,1,2));manager.owner=owner.getUUID();manager.state.timezone="UTC";
        ManagerState.Hall hall=new ManagerState.Hall();hall.name="Public hall";manager.state.halls.add(hall);
        InfoTile panel=PanelGameTests.place(h,owner,new BlockPos(2,1,2));panel.bind(owner,manager,hall.id);
        return new Fixture(owner,manager,hall,panel);
    }
    private static FilmCatalog.Snapshot snapshot(Fixture f,String title,String poster){
        FilmCatalog catalog=f.manager.library.catalog();
        var publication=new FilmCatalog.Publication(f.owner.getUUID().toString(),f.owner.getGameProfile().getName(),f.manager.identity.toString(),f.manager.owner.toString(),poster);
        var film=catalog.create(catalog.revision(),new FilmCatalog.Draft(title,poster,List.of(MEDIA)),publication);
        film=catalog.observeTrusted(film.id(),film.revision(),0,MEDIA,false,120000,
                new FilmCatalog.Provenance(f.owner.getUUID().toString(),f.manager.identity.toString(),UUID.randomUUID().toString(),MEDIA,UUID.randomUUID().toString()));
        return catalog.snapshot(film.id(),film.revision());
    }
    private static ManagerState.Show show(Fixture f,FilmCatalog.Snapshot snapshot,long start,ManagerState.Status status){
        ManagerState.Show show=new ManagerState.Show();show.hallId=f.hall.id;show.start=start;show.end=start+snapshot.film().requireDurationMs();show.status=status;
        show.title=snapshot.film().title();show.url=snapshot.film().segments().getFirst().sourceKey();show.filmId=snapshot.film().id();
        show.filmRevision=snapshot.film().revision();show.filmSnapshot=snapshot.id();show.filmDurationMs=snapshot.totalDurationMs();
        f.manager.state.shows.add(show);return show;
    }
    private static PanelSettings layout(PanelSettings.Layout layout,String poster){
        return new PanelSettings(layout,"","","","PRIVATE_EDITOR_NEXT","PRIVATE_EDITOR_FOOTER",poster,0xffffff,0,0xabcdef,100,PanelSettings.ALL);
    }
    private static void published(GameTestHelper h,InfoTile tile,String poster){
        tile.refreshNow();var view=PanelNetwork.guestData(tile);var tag=tile.getUpdateTag(h.getLevel().registryAccess());
        h.assertTrue(tile.approvedPoster().equals(poster)&&tile.publishedPoster.equals(poster)
                &&view.settings().poster().equals(poster)&&tag.getString("published_poster").equals(poster)
                &&PanelSettings.read(tag.getCompound("panel_settings")).poster().equals(poster),"all guest publication paths agree");
        h.assertTrue(poster.isEmpty()?tag.getString("poster_publication").isEmpty():PosterPublicationState.validToken(tag.getString("poster_publication")),"opaque publication token follows source authorization");
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void film_guest_poster_uses_immutable_current_next_selection_and_explicit_override(GameTestHelper h){
        Fixture f=fixture(h);long now=System.currentTimeMillis();
        // This fixture has no TMS lease: automatic publication must select an eligible future plan.
        var currentSnapshot=snapshot(f,"First planned film",POSTER);var current=show(f,currentSnapshot,now+60000,ManagerState.Status.PENDING);
        var nextSnapshot=snapshot(f,"Next film",OTHER_POSTER);show(f,nextSnapshot,now+180000,ManagerState.Status.PENDING);
        f.panel.panel=layout(PanelSettings.Layout.POSTER_LEFT,"");published(h,f.panel,POSTER);
        h.assertTrue(f.panel.panel.poster().isEmpty(),"automatic source never overwrites owner's blank poster override");
        h.assertTrue(PanelNetwork.guestData(f.panel).snapshot().rows().getFirst().id().equals(current.id),"poster belongs to first public programme");
        String guest=PanelNetwork.JSON.toJson(PanelNetwork.guestData(f.panel))+f.panel.getUpdateTag(h.getLevel().registryAccess());
        for(String secret:List.of(MEDIA,currentSnapshot.id(),currentSnapshot.film().id(),f.owner.getUUID().toString(),f.owner.getGameProfile().getName(),"PRIVATE_EDITOR_NEXT","PRIVATE_EDITOR_FOOTER","filmSnapshot"))
            h.assertFalse(guest.contains(secret),"guest payload excludes "+secret);
        var film=currentSnapshot.film();var changedPublication=new FilmCatalog.Publication(f.owner.getUUID().toString(),f.owner.getGameProfile().getName(),f.manager.identity.toString(),f.manager.owner.toString(),OTHER_POSTER);
        f.manager.library.catalog().update(film.id(),film.revision(),new FilmCatalog.Draft("Edited library title",OTHER_POSTER,List.of(MEDIA)),changedPublication);
        published(h,f.panel,POSTER);
        h.assertTrue(PanelNetwork.guestData(f.panel).snapshot().rows().getFirst().title().equals("First planned film"),"catalog edits do not rewrite scheduled poster or title");
        f.panel.panel=layout(PanelSettings.Layout.TEXT,"");published(h,f.panel,POSTER);
        h.assertTrue(PanelNetwork.guestData(f.panel).settings().layout()==PanelSettings.Layout.TEXT,"automatic poster never changes explicit text layout");
        f.panel.configure(f.owner,PanelGroup.of(f.panel).signature(),layout(PanelSettings.Layout.POSTER_TOP,OTHER_POSTER));published(h,f.panel,OTHER_POSTER);
        f.panel.configure(f.owner,PanelGroup.of(f.panel).signature(),layout(PanelSettings.Layout.POSTER_TOP,""));published(h,f.panel,POSTER);
        current.status=ManagerState.Status.COMPLETED;published(h,f.panel,OTHER_POSTER);
        h.assertTrue(PanelNetwork.guestData(f.panel).snapshot().rows().getFirst().title().equals("Next film"),"retired first plan hands poster to next programme");
        current.start=now-10000;current.end=current.start+currentSnapshot.totalDurationMs();
        current.status=ManagerState.Status.RUNNING;current.filmSnapshot="";published(h,f.panel,"");
        h.assertTrue(PanelNetwork.guestData(f.panel).snapshot().rows().getFirst().id().equals(current.id),"current legacy programme does not borrow a later film poster");
        current.status=ManagerState.Status.COMPLETED;current.filmSnapshot=currentSnapshot.id();
        f.panel.hallId="missing";published(h,f.panel,"");f.panel.hallId=f.hall.id;
        f.panel.scope=PanelScope.LOBBY;published(h,f.panel,"");
        f.panel.scope=PanelScope.MANUAL;published(h,f.panel,"");f.panel.panel=f.panel.panel.withPoster(POSTER);published(h,f.panel,POSTER);
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void film_guest_poster_revocation_replacement_and_asset_lease_fail_closed(GameTestHelper h){
        Fixture f=fixture(h);long now=System.currentTimeMillis();var a=show(f,snapshot(f,"First",POSTER),now+60000,ManagerState.Status.PENDING);
        var b=show(f,snapshot(f,"Second",POSTER),now+180000,ManagerState.Status.PENDING);published(h,f.panel,POSTER);
        h.assertTrue(PanelNetwork.guestData(f.panel).snapshot().rows().getFirst().id().equals(a.id),"first future plan supplies the initial publication");
        String first=f.panel.publishedPosterToken;var firstLease=new AssetPanelLease(f.panel,PanelGroup.of(f.panel).signature());
        h.assertTrue(firstLease.downloadCurrent(f.owner,POSTER),"current approved programme permits a bounded poster transfer");
        a.status=ManagerState.Status.COMPLETED;published(h,f.panel,POSTER);
        h.assertTrue(PanelNetwork.guestData(f.panel).snapshot().rows().getFirst().id().equals(b.id),"next future plan replaces the retired publication even at the same poster URI");
        h.assertFalse(first.equals(f.panel.publishedPosterToken)||firstLease.downloadCurrent(f.owner,POSTER),"same-URI next snapshot invalidates pending publication and transfer");
        String beforeRevoke=f.panel.publishedPosterToken;UUID originalOwner=f.manager.owner;f.manager.owner=UUID.randomUUID();published(h,f.panel,"");
        h.assertFalse(f.panel.permitsPosterTarget(POSTER,"https://i.imgur.com/allowed.png"),"new whitelisted target cannot revive revoked publication");
        f.manager.owner=originalOwner;published(h,f.panel,POSTER);
        h.assertFalse(beforeRevoke.equals(f.panel.publishedPosterToken),"restoring owner creates new callback authority");
        f.manager.state.shows.clear();show(f,snapshot(f,"Local private poster","file:/PRIVATE_POSTER_PATH.png"),now+60000,ManagerState.Status.PENDING);published(h,f.panel,"");
        f.manager.state.shows.clear();String asset=AssetReference.ofHash("a".repeat(64));show(f,snapshot(f,"Unknown asset",asset),now+60000,ManagerState.Status.PENDING);published(h,f.panel,"");
        h.assertFalse(new AssetPanelLease(f.panel,PanelGroup.of(f.panel).signature()).downloadCurrent(f.owner,asset),"knowledge of a private asset ID grants neither publication nor transfer");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void film_guest_redirect_rechecks_original_author_and_whitelisted_targets(GameTestHelper h){
        Fixture f=fixture(h);var ops=h.getLevel().getServer().getPlayerList().getOps();var previous=ops.get(f.owner.getGameProfile());
        String source="https://film-poster-private.invalid/image",allowed="https://i.imgur.com/image.png",other="https://film-poster-cdn.invalid/image";
        try{
            ops.add(new ServerOpListEntry(f.owner.getGameProfile(),4,false));
            show(f,snapshot(f,"Author granted poster",source),System.currentTimeMillis()+60000,ManagerState.Status.PENDING);published(h,f.panel,source);
            h.assertTrue(f.panel.permitsPosterTarget(source,other),"currently authorized film author permits CDN hop");
            h.assertFalse(f.panel.permitsPosterTarget(source+"?forged",other),"guest cannot substitute initial film poster source");
            ops.remove(f.owner.getGameProfile());published(h,f.panel,"");
            h.assertFalse(f.panel.permitsPosterTarget(source,allowed),"whitelisted target does not bypass revoked original author source");
            ops.add(new ServerOpListEntry(f.owner.getGameProfile(),4,false));published(h,f.panel,source);
            f.panel.panel=f.panel.panel.withPoster(allowed);published(h,f.panel,allowed);
            h.assertFalse(f.panel.permitsPosterTarget(allowed,other),"explicit override cannot borrow automatic film author's grant");
        }finally{if(previous==null)ops.remove(f.owner.getGameProfile());else ops.add(previous);}
        h.succeed();
    }
}
