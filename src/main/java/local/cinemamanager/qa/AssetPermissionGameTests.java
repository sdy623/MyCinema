package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.InfoTile;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoBlock;
import local.cinemamanager.advertising.AdvertisingGeometry;
import local.cinemamanager.panels.*;
import local.cinemamanager.panels.assets.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Explicit simulated actors exercise the same target lease; production network still rejects all fake actors. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class AssetPermissionGameTests {
    private static ServerPlayer actor(GameTestHelper h,String name){var actor=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));actor.setGameMode(GameType.SURVIVAL);actor.setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());return actor;}
    @GameTest(template="empty",timeoutTicks=40)
    public static void private_asset_transfer_lease_rechecks_owner_identity_group_and_range(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"isolated simulated actors only");
        ServerPlayer owner=actor(h,"AssetOwnerQA"),stranger=actor(h,"AssetStrangerQA");InfoTile tile=PanelGameTests.place(h,owner,new BlockPos(2,1,2));
        var lease=new AssetPanelLease(tile,PanelGroup.of(tile).signature());
        h.assertTrue(lease.uploadCurrent(owner),"live local owner may continue an upload");h.assertFalse(lease.uploadCurrent(stranger),"another account cannot upload into this panel");
        UUID ownerId=tile.owner;tile.owner=stranger.getUUID();h.assertFalse(lease.uploadCurrent(owner)||lease.uploadCurrent(stranger),"captured owner never transfers to a new owner");tile.owner=ownerId;
        UUID identity=tile.identity;tile.identity=UUID.randomUUID();h.assertFalse(lease.uploadCurrent(owner),"replacement identity invalidates continuation");tile.identity=identity;
        tile.panelRevision++;h.assertFalse(lease.uploadCurrent(owner),"group revision invalidates continuation");tile.panelRevision--;
        owner.setPos(tile.getBlockPos().getCenter().add(8.01,0,0));h.assertFalse(lease.uploadCurrent(owner),"upload remains within eight metres");owner.setPos(tile.getBlockPos().getCenter());
        owner.setGameMode(GameType.SPECTATOR);h.assertFalse(lease.uploadCurrent(owner),"spectator ownership does not confer edit permission");owner.setGameMode(GameType.SURVIVAL);
        h.assertFalse(new AssetPanelLease(tile,UUID.randomUUID().toString()).uploadCurrent(owner),"forged group signature denied");
        h.assertFalse(PanelAssets.mayUseAsset(owner,tile,AssetReference.ofHash("a".repeat(64))),"simulated network actors do not obtain production asset authority");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void private_asset_download_lease_requires_current_published_source_and_loaded_instance(GameTestHelper h){
        ServerPlayer owner=actor(h,"AssetPublishQA"),viewer=actor(h,"AssetViewerQA");InfoTile tile=PanelGameTests.place(h,owner,new BlockPos(2,1,2));
        String approved="https://i.imgur.com/asset-permission-fixture.png";tile.panel=PanelSettings.DEFAULT.withPoster(approved);
        var lease=new AssetPanelLease(tile,PanelGroup.of(tile).signature());
        h.assertTrue(lease.downloadCurrent(viewer,approved),"lease allows a currently approved public source for a nearby viewer");
        h.assertFalse(lease.downloadCurrent(viewer,approved+"?substituted"),"viewer cannot substitute a hash or URI");
        viewer.setPos(tile.getBlockPos().getCenter().add(64.01,0,0));h.assertFalse(lease.downloadCurrent(viewer,approved),"download range limited to sixty-four metres");viewer.setPos(tile.getBlockPos().getCenter());
        UUID ownerId=tile.owner;tile.owner=UUID.randomUUID();h.assertFalse(lease.downloadCurrent(viewer,approved),"ownership changes invalidate even a still-public source");tile.owner=ownerId;
        tile.panel=tile.panel.withPoster("");h.assertFalse(lease.downloadCurrent(viewer,approved),"unpublished source stops continuation");tile.panel=tile.panel.withPoster(approved);
        InfoTile replacement=new InfoTile(tile.getBlockPos(),tile.getBlockState());replacement.identity=tile.identity;replacement.owner=tile.owner;replacement.panel=tile.panel;
        h.getLevel().removeBlockEntity(tile.getBlockPos());h.getLevel().setBlockEntity(replacement);h.assertFalse(lease.downloadCurrent(viewer,approved),"same IDs on a new loaded instance never revive an old lease");
        BlockPos far=new BlockPos(100000,64,100000);h.assertFalse(h.getLevel().hasChunkAt(far),"far target starts unloaded");h.assertTrue(PanelGroup.loaded(h.getLevel(),far)==null,"loaded lookup refuses missing chunk");h.assertFalse(h.getLevel().hasChunkAt(far),"no force loading");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void private_asset_ranges_follow_expanded_advertising_surface(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"isolated simulated actors only");
        ServerPlayer owner=actor(h,"AssetSurfaceQA");BlockPos pos=new BlockPos(2,1,2);
        h.setBlock(pos.south(),net.minecraft.world.level.block.Blocks.STONE);
        h.setBlock(pos,CinemaManager.AD_PRINTED.get().defaultBlockState().setValue(InfoBlock.FACING,Direction.NORTH));
        InfoTile tile=h.getBlockEntity(pos);tile.owner=owner.getUUID();tile.advertising=new AdvertisingGeometry(0,0,128,8);
        String source="https://i.imgur.com/asset-surface-fixture.png";tile.panel=PanelSettings.DEFAULT.withPoster(source);
        var lease=new AssetPanelLease(tile,PanelGroup.of(tile).signature());var body=tile.advertising.body(tile.getBlockPos(),Direction.NORTH,false);
        owner.setPos(body.minX+.25,(body.minY+body.maxY)/2,body.minZ-.25);
        h.assertTrue(owner.distanceToSqr(tile.getBlockPos().getCenter())>64,"far edge is outside the anchor's old edit radius");
        h.assertTrue(tile.permits(owner,8)&&lease.uploadCurrent(owner),"surface edit permission and upload continuation agree");
        owner.setPos(body.minX-63,(body.minY+body.maxY)/2,body.minZ);
        h.assertTrue(owner.distanceToSqr(tile.getBlockPos().getCenter())>4096&&lease.downloadCurrent(owner,source),"viewer range is measured from the same advertising surface");
        owner.setPos(body.minX-64.01,(body.minY+body.maxY)/2,body.minZ);
        h.assertFalse(lease.downloadCurrent(owner,source),"expanded surface does not remove the sixty-four metre viewer bound");h.succeed();
    }
}
