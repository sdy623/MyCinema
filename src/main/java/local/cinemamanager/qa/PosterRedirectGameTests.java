package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Real loaded-server authorization; no HTTP fetches, no real user plans or grants. */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class PosterRedirectGameTests {
    private static final String SOURCE="https://i.imgur.com/initial-no-extension?signature=A%2Fb";
    private static final String ALLOWED="https://i.imgur.com/final?format=webp";
    private static final String OTHER="https://poster-redirect-fixture.invalid/final?signature=C%2Fd";
    private static ServerPlayer actor(GameTestHelper h,String name){
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));p.setGameMode(GameType.SURVIVAL);
        p.setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());return p;
    }
    private static boolean allow(ServerPlayer viewer,InfoTile tile,String source,String target){
        return PosterRedirectNetwork.allow(viewer,new PosterRedirectNetwork.Request(tile.getBlockPos(),tile.identity.toString(),source,target,UUID.randomUUID().toString()));
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void poster_redirect_rechecks_author_target_revocation_and_instance(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"isolated opt-in fixtures only");
        var owner=actor(h,"PosterHopOwner");var viewer=actor(h,"PosterHopViewer");
        var ops=h.getLevel().getServer().getPlayerList().getOps();var old=ops.get(owner.getGameProfile());
        try{
            ops.add(new ServerOpListEntry(owner.getGameProfile(),4,false));
            InfoTile tile=PanelGameTests.place(h,owner,new BlockPos(2,1,2));
            tile.configure(owner,PanelGroup.of(tile).signature(),PanelSettings.DEFAULT.withPoster(SOURCE));
            h.assertFalse(viewer.hasPermissions(4),"viewer is not an operator");
            h.assertTrue(allow(viewer,tile,SOURCE,OTHER),"offline currently authorized author permits a new CDN domain");
            ops.remove(owner.getGameProfile());
            h.assertTrue(tile.approvedPoster().equals(SOURCE),"initial white-listed source remains publishable after bypass revocation");
            h.assertFalse(allow(viewer,tile,SOURCE,OTHER),"cached final outside-list destination is denied after author revocation");
            h.assertTrue(allow(viewer,tile,SOURCE,ALLOWED),"ordinary whitelist destination remains permitted");
            ops.add(new ServerOpListEntry(owner.getGameProfile(),4,false));
            UUID originalOwner=tile.owner;tile.owner=UUID.randomUUID();
            h.assertFalse(allow(viewer,tile,SOURCE,OTHER),"changed owner cannot use prior grant for redirected target");tile.owner=originalOwner;
            h.assertTrue(allow(viewer,tile,SOURCE,OTHER),"unchanged binding and current author permission work");
            h.assertFalse(allow(viewer,tile,SOURCE+"x",OTHER),"client cannot substitute initial URI");
            tile.panel=tile.panel.withPoster(ALLOWED);
            h.assertFalse(allow(viewer,tile,SOURCE,OTHER),"source change invalidates pending hop");
            h.assertFalse(allow(viewer,tile,ALLOWED,OTHER),"old saved grant does not authorize new source");
        }finally{if(old==null)ops.remove(owner.getGameProfile());else ops.add(old);}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void poster_redirect_legacy_guest_bounds_and_no_chunk_load(GameTestHelper h){
        var owner=actor(h,"PosterHopLegacy");var viewer=actor(h,"PosterHopGuest");
        InfoTile tile=PanelGameTests.place(h,owner,new BlockPos(2,1,2));
        tile.panel=PanelSettings.DEFAULT.withPoster(SOURCE); // Saved legacy fixture intentionally has no author grant.
        h.assertTrue(allow(viewer,tile,SOURCE,ALLOWED),"legacy whitelisted source may redirect to whitelisted destination");
        h.assertFalse(allow(viewer,tile,SOURCE,OTHER),"legacy source has no inherited bypass");
        for(String bad:new String[]{"file:/tmp/poster.png","resource:minecraft:textures/block/stone.png","https://name:secret@example.com/a","ftp://example.com/a"})
            h.assertFalse(allow(viewer,tile,SOURCE,bad),"redirect target scheme and embedded credentials remain constrained");
        h.assertFalse(PosterRedirectNetwork.allow(viewer,new PosterRedirectNetwork.Request(tile.getBlockPos(),UUID.randomUUID().toString(),SOURCE,ALLOWED,UUID.randomUUID().toString())),"stale panel identity denied");
        viewer.setPos(tile.getBlockPos().getCenter().add(65,0,0));h.assertFalse(allow(viewer,tile,SOURCE,ALLOWED),"viewer outside render authorization range denied");
        var far=new BlockPos(100000,64,100000);h.assertFalse(h.getLevel().hasChunkAt(far),"far chunk initially absent");
        h.assertFalse(PosterRedirectNetwork.allow(owner,new PosterRedirectNetwork.Request(far,tile.identity.toString(),SOURCE,ALLOWED,UUID.randomUUID().toString())),"unloaded target denied");
        h.assertFalse(h.getLevel().hasChunkAt(far),"authorization never creates a chunk ticket or load");
        h.succeed();
    }
}
