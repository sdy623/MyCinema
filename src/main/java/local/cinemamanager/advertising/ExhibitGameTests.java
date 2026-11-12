package local.cinemamanager.advertising;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.*;
import local.cinemamanager.panels.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Explicit simulated actors in independent worlds; native layout/video checks remain separate. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ExhibitGameTests {
    private static ServerPlayer actor(GameTestHelper h){var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"ExhibitFixture"));p.setGameMode(GameType.SURVIVAL);p.setPos(h.absolutePos(new BlockPos(8,3,6)).getCenter());return p;}
    private static InfoTile unit(GameTestHelper h,ServerPlayer owner,BlockPos rel,boolean led){
        BlockPos p=h.absolutePos(rel);h.getLevel().setBlockAndUpdate(p.south(),Blocks.STONE.defaultBlockState());
        h.getLevel().setBlockAndUpdate(p,(led?CinemaManager.AD_LED.get():CinemaManager.AD_PRINTED.get()).defaultBlockState().setValue(AdvertisingBlock.FACING,Direction.NORTH));
        var tile=(InfoTile)h.getLevel().getBlockEntity(p);tile.initializePlacement(owner.getUUID());tile.scope=PanelScope.MANUAL;return tile;
    }
    private static void rejects(GameTestHelper h,Runnable command,String label){boolean rejected=false;try{command.run();}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,label);}

    @GameTest(template="empty",timeoutTicks=50)
    public static void exhibit_explicit_assembly_preserves_units_identity_and_saved_geometry(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=unit(h,owner,new BlockPos(8,3,8),true);
        var others=List.of(unit(h,owner,new BlockPos(7,3,8),true),unit(h,owner,new BlockPos(8,4,8),true),unit(h,owner,new BlockPos(7,4,8),true));
        UUID identity=root.identity;var plan=AdvertisingAssembly.inspect(root);h.assertTrue(plan!=null&&plan.units().size()==4,"filled compatible 2D units have a four-unit review plan");
        AdvertisingAssembly.assemble(root,owner,plan.signature());
        h.assertTrue(root.identity.equals(identity)&&root.assembledUnits==4&&root.advertising.width()==16&&root.advertising.height()==16,"one identity with exact 2x2 geometry and original unit count");
        for(InfoTile old:others)h.assertTrue(h.getLevel().getBlockEntity(old.getBlockPos()) instanceof AdvertisingPartTile p&&p.root()==root,"other roots are reciprocal proxies, not second renderers");
        var drops=net.minecraft.world.level.block.Block.getDrops(root.getBlockState(),h.getLevel(),root.getBlockPos(),root);
        h.assertTrue(drops.stream().filter(s->s.is(CinemaManager.AD_LED_ITEM.get())).mapToInt(s->s.getCount()).sum()==4,"ordinary block drops return four original units");
        var nbt=root.saveWithFullMetadata(h.getLevel().registryAccess());InfoTile restored=new InfoTile(root.getBlockPos(),root.getBlockState());restored.loadWithComponents(nbt,h.getLevel().registryAccess());
        h.getLevel().removeBlockEntity(root.getBlockPos());h.getLevel().setBlockEntity(restored);
        h.assertTrue(restored.identity.equals(identity)&&restored.assembledUnits==4&&restored.advertising.equals(root.advertising),"full root NBT retains assembly and physical dimensions");
        for(InfoTile old:others)h.assertTrue(((AdvertisingPartTile)h.getLevel().getBlockEntity(old.getBlockPos())).root()==restored,"saved proxy resolves reloaded exact root");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void exhibit_assembly_rejects_stale_foreign_holes_and_rolls_back(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=unit(h,owner,new BlockPos(8,3,8),true),neighbor=unit(h,owner,new BlockPos(7,3,8),true);
        var first=AdvertisingAssembly.inspect(root);neighbor.panelRevision++;
        rejects(h,()->AdvertisingAssembly.assemble(root,owner,first.signature()),"changed source revision invalidates confirmation");
        neighbor.owner=UUID.randomUUID();h.assertTrue(AdvertisingAssembly.inspect(root)==null,"foreign owner is not silently consumed");neighbor.owner=owner.getUUID();
        var current=AdvertisingAssembly.inspect(root);var oldRoot=root.saveWithFullMetadata(h.getLevel().registryAccess());var oldNeighbor=neighbor.saveWithFullMetadata(h.getLevel().registryAccess());
        rejects(h,()->AdvertisingAssembly.assembleUsing(root,owner,current.signature(),(pos,state)->false),"injected write failure aborts atomic assembly");
        var reread=(InfoTile)h.getLevel().getBlockEntity(root.getBlockPos());var rereadNeighbor=(InfoTile)h.getLevel().getBlockEntity(neighbor.getBlockPos());
        h.assertTrue(reread.saveWithFullMetadata(h.getLevel().registryAccess()).equals(oldRoot)&&rereadNeighbor.saveWithFullMetadata(h.getLevel().registryAccess()).equals(oldNeighbor),"all original identities/content restored after failed write");
        unit(h,owner,new BlockPos(8,4,8),true);h.assertTrue(AdvertisingAssembly.inspect(reread)==null,"L shape remains separate; no missing cell overwritten");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void exhibit_information_mode_manual_preservation_and_public_no_media_path(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=unit(h,owner,new BlockPos(8,3,8),true);
        root.screenMode(owner,PanelGroup.of(root).signature(),ScreenMode.INFORMATION);
        var settings=new PanelSettings(PanelSettings.Layout.POSTER_TOP,"Hall 1","Display title","18:45","private-manager-next","private-manager-footer","",0xffffff,0x111111,0xffcc00,100,PanelSettings.PUBLIC,"45 min","19:30");
        root.configure(owner,PanelGroup.of(root).signature(),settings,PanelScope.MANUAL);
        h.assertTrue(root.screenMode==ScreenMode.INFORMATION&&root.panel.title().equals("Display title")&&root.panel.manualTime().equals("18:45–19:30"),"information mode reuses standalone manual title/time without a controller");
        String publicTag=root.getUpdateTag(h.getLevel().registryAccess()).toString();h.assertFalse(publicTag.contains("private-manager-")||publicTag.contains("mirror_board_owner"),"public board tag excludes owner-only notes and binding authorship");
        root.screenMode(owner,PanelGroup.of(root).signature(),ScreenMode.PICTURE);
        h.assertTrue(root.panel.equals(settings)&&root.manager==null&&root.mirror==null,"switching content retains editable picture/text but releases subscriptions");
        root.screenMode(owner,PanelGroup.of(root).signature(),ScreenMode.MEDIA);
        h.assertTrue(root.approvedMirror()==null&&root.approvedPoster().isEmpty(),"unbound media mode neither exposes stale poster nor invents a player");
        InfoTile printed=unit(h,owner,new BlockPos(6,3,8),false);
        rejects(h,()->printed.screenMode(owner,PanelGroup.of(printed).signature(),ScreenMode.MEDIA),"printed ambient sign cannot become an emissive movie screen");
        h.assertTrue(printed.screenMode==ScreenMode.PICTURE,"denied mode mutation is atomic");
        h.succeed();
    }
}
