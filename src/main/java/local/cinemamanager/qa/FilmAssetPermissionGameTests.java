package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.panels.assets.AssetFilmLease;
import local.cinemamanager.panels.assets.AssetNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.List;
import java.util.UUID;

/** Simulated actors exercise the production lease; production packet admission still rejects fake actors. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FilmAssetPermissionGameTests {
    @GameTest(template="empty",timeoutTicks=40)
    public static void film_asset_lease_requires_exact_manager_owner_controller_and_revisions(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"isolated simulated actors only");
        var owner=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"FilmAssetOwner"));
        var stranger=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"FilmAssetOther"));
        owner.setGameMode(GameType.SURVIVAL);stranger.setGameMode(GameType.SURVIVAL);
        BlockPos pos=new BlockPos(2,1,2);h.setBlock(pos,CinemaManager.MANAGER.get());
        ManagerTile manager=h.getBlockEntity(pos);manager.owner=owner.getUUID();
        owner.setPos(manager.getBlockPos().getCenter());stranger.setPos(manager.getBlockPos().getCenter());
        var target=new AssetNetwork.FilmTarget(manager.getBlockPos(),manager.identity.toString(),"",manager.library.catalog().revision(),0);
        var lease=new AssetFilmLease(manager,target);
        h.assertTrue(lease.uploadCurrent(owner),"new film upload needs only a real manager, never an InfoTile");
        h.assertFalse(lease.uploadCurrent(stranger),"stranger denied");
        UUID oldOwner=manager.owner;manager.owner=stranger.getUUID();
        h.assertFalse(lease.uploadCurrent(owner)||lease.uploadCurrent(stranger),"transfer cannot move to a replacement owner");manager.owner=oldOwner;
        UUID oldId=manager.identity;manager.identity=UUID.randomUUID();h.assertFalse(lease.uploadCurrent(owner),"replacement identity invalidates transfer");manager.identity=oldId;
        owner.setPos(manager.getBlockPos().getCenter().add(9,0,0));h.assertFalse(lease.uploadCurrent(owner),"remote actor needs current controller authority");owner.setPos(manager.getBlockPos().getCenter());
        owner.setGameMode(GameType.SPECTATOR);h.assertFalse(lease.uploadCurrent(owner),"spectator denied");owner.setGameMode(GameType.SURVIVAL);
        var film=manager.library.catalog().create(target.catalogRevision(),new FilmCatalog.Draft("Film","",List.of("https://example.com/film.mp4")));
        h.assertFalse(lease.uploadCurrent(owner),"catalog edit invalidates in-flight new-film upload");
        var existing=new AssetNetwork.FilmTarget(manager.getBlockPos(),manager.identity.toString(),film.id(),manager.library.catalog().revision(),film.revision());
        var existingLease=new AssetFilmLease(manager,existing);
        h.assertTrue(existingLease.uploadCurrent(owner),"existing film revision accepted");
        h.assertFalse(new AssetFilmLease(manager,new AssetNetwork.FilmTarget(existing.pos(),existing.identity(),film.id(),existing.catalogRevision(),film.revision()+1)).uploadCurrent(owner),"forged film revision denied");
        h.assertFalse(new AssetFilmLease(manager,new AssetNetwork.FilmTarget(existing.pos(),existing.identity(),UUID.randomUUID().toString(),existing.catalogRevision(),1)).uploadCurrent(owner),"missing film denied");
        manager.library.catalog().update(film.id(),film.revision(),new FilmCatalog.Draft("Changed","",List.of("https://example.com/film.mp4")));
        h.assertFalse(existingLease.uploadCurrent(owner),"film update during validation prevents commit");
        var fresh=new AssetFilmLease(manager,new AssetNetwork.FilmTarget(manager.getBlockPos(),manager.identity.toString(),"",manager.library.catalog().revision(),0));
        ManagerTile replacement=new ManagerTile(manager.getBlockPos(),manager.getBlockState());replacement.owner=manager.owner;replacement.identity=manager.identity;
        h.getLevel().removeBlockEntity(manager.getBlockPos());h.getLevel().setBlockEntity(replacement);
        h.assertFalse(fresh.uploadCurrent(owner),"same identity on another loaded instance cannot revive the lease");
        h.succeed();
    }
}
