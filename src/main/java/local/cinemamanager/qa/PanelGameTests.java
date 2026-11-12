package local.cinemamanager.qa;

import local.cinemamanager.*;
import local.cinemamanager.panels.*;
import local.cinemamanager.model.ManagerState;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Actual loaded-world registration/lifecycle checks; test actors do not grant production authority. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class PanelGameTests {
    static ServerPlayer actor(GameTestHelper h){var p=h.makeMockServerPlayerInLevel();p.setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CinemaManager.INFO_ITEM.get(),16));return p;}
    static InfoTile place(GameTestHelper h,ServerPlayer actor,BlockPos wall){BlockPos support=wall.south();h.setBlock(support,Blocks.STONE);var context=new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(h.absolutePos(support).getCenter().add(0,0,-.5),Direction.NORTH,h.absolutePos(support),false));var result=((BlockItem)CinemaManager.INFO_ITEM.get()).place(new BlockPlaceContext(context));h.assertTrue(result.consumesAction(),"real item placement accepted");h.assertTrue(h.getBlockState(wall).is(CinemaManager.WALL_INFO.get()),"side placement uses wall state");return h.getBlockEntity(wall);}
    static void rejects(GameTestHelper h,Runnable action,String why){boolean denied=false;try{action.run();}catch(IllegalArgumentException e){denied=true;}h.assertTrue(denied,why);}
    @GameTest(template="empty",timeoutTicks=50)
    public static void wall_panel_item_support_and_group_lifecycle(GameTestHelper h){
        var p=actor(h);InfoTile a=place(h,p,new BlockPos(2,1,2)),b=place(h,p,new BlockPos(2,2,2)),c=place(h,p,new BlockPos(2,3,2));
        h.assertTrue(a.owner.equals(p.getUUID())&&a.getBlockState().getValue(InfoBlock.FACING)==Direction.NORTH,"owner and outward facing recorded");
        h.assertTrue(PanelGroup.of(b).root()==a&&PanelGroup.of(b).height()==3,"real adjacent vertical column joins");
        h.assertTrue(CinemaManager.WALL_INFO.get().asItem()==CinemaManager.INFO_ITEM.get(),"wall pick/drop item maps to same inventory identity");
        h.assertTrue(h.getLevel().noCollision(new AABB(h.absolutePos(new BlockPos(2,1,2))).deflate(.05)),"thin panel does not block movement");
        h.setBlock(new BlockPos(2,2,3),Blocks.AIR);
        h.runAfterDelay(2,()->{h.assertTrue(h.getBlockState(new BlockPos(2,2,2)).isAir(),"unsupported middle segment removed");h.assertTrue(PanelGroup.of(a).height()==1&&PanelGroup.of(c).height()==1,"remaining segments split into standalone columns");h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=30)
    public static void panel_binding_settings_permissions_and_stale_snapshot(GameTestHelper h){
        var p=actor(h);InfoTile a=place(h,p,new BlockPos(2,1,2)),b=place(h,p,new BlockPos(2,2,2));
        h.setBlock(new BlockPos(4,1,2),CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(new BlockPos(4,1,2));manager.owner=p.getUUID();var other=new ManagerState.Hall();other.name="Other hall";other.terminalIdentity=UUID.randomUUID().toString();other.terminalPos=h.absolutePos(new BlockPos(6,1,2)).asLong();manager.state.halls.add(other);var hall=new ManagerState.Hall();hall.name="Bound hall";hall.terminalIdentity=UUID.randomUUID().toString();hall.terminalPos=h.absolutePos(new BlockPos(7,1,2)).asLong();hall.current="Current film";hall.deviceState="Paused";manager.state.halls.add(hall);manager.state.timezone="Asia/Tokyo";
        var next=new ManagerState.Show();next.hallId=hall.id;next.title="Next film";next.url="https://example.com/private-film.mkv";next.start=System.currentTimeMillis()+60000;next.end=next.start+60000;manager.state.shows.add(next);
        a.bind(p,manager,hall.id);h.assertTrue(b.managerIdentity.equals(manager.identity)&&b.hallId.equals(hall.id),"binding propagates atomically to column");
        var publicView=PanelNetwork.guestData(a);h.assertTrue(publicView.snapshot().rows().size()==1&&publicView.snapshot().rows().getFirst().hall().equals(hall.name)&&!PanelNetwork.JSON.toJson(publicView).contains(next.url),"read-only detail contains only bound-hall public rows");
        String old=PanelGroup.of(a).signature();var custom=new PanelSettings(PanelSettings.Layout.POSTER_TOP,"Custom hall","Custom title","Custom time","","Footer","",0x123456,0,0xffffff,120,63);a.configure(p,old,custom);h.assertTrue(b.panel.equals(custom),"all joined segments get settings");rejects(h,()->a.configure(p,old,PanelSettings.DEFAULT),"stale saved configuration refused");
        h.assertTrue(a.publicSnapshot.rows().getFirst().title().equals("Next film")&&a.panelFields.contains("Paused"),"real schedule rows and owner diagnostics are separate from overrides");h.assertTrue(hall.current.equals("Current film")&&!hall.override&&!manager.state.schedulerEnabled,"display customization never changes playback or automatic state");
        UUID owner=a.owner;a.owner=UUID.randomUUID();rejects(h,()->a.configure(p,PanelGroup.of(a).signature(),PanelSettings.DEFAULT),"owner change rejects prior actor");a.owner=owner;
        BlockPos far=new BlockPos(100000,64,100000);h.assertFalse(h.getLevel().hasChunkAt(far),"distant region starts unloaded");h.assertTrue(PanelGroup.loaded(h.getLevel(),far)==null,"loaded-only lookup refuses missing region");h.assertFalse(h.getLevel().hasChunkAt(far),"panel lookup creates no chunk ticket");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=30)
    public static void panel_save_reload_offline_and_existing_cube_compatibility(GameTestHelper h){
        var p=actor(h);InfoTile a=place(h,p,new BlockPos(2,1,2));var custom=new PanelSettings(PanelSettings.Layout.TEXT,"Saved hall","Saved title","","Next override","Footer","",0xeeeeee,0,0xffff00,90,31);a.configure(p,PanelGroup.of(a).signature(),custom);
        var nbt=a.saveWithFullMetadata(h.getLevel().registryAccess());InfoTile copy=new InfoTile(a.getBlockPos(),a.getBlockState());copy.loadWithComponents(nbt,h.getLevel().registryAccess());h.assertTrue(copy.panel.equals(custom)&&copy.panelGroup.equals(a.panelGroup)&&copy.owner.equals(a.owner)&&copy.panelRevision==a.panelRevision,"real tile NBT restores configuration/group/ownership/revision");
        a.manager=h.absolutePos(new BlockPos(7,1,2));a.managerIdentity=UUID.randomUUID();a.refreshNow();h.assertTrue(a.publicSnapshot.rows().isEmpty()&&a.panelFields.getFirst().contains("panel.offline"),"missing controller clears public rows and keeps offline diagnosis private");
        h.setBlock(new BlockPos(5,1,2),CinemaManager.INFO.get());InfoTile legacy=h.getBlockEntity(new BlockPos(5,1,2));var legacyNbt=legacy.saveWithFullMetadata(h.getLevel().registryAccess());legacyNbt.remove("panel_settings");legacyNbt.remove("panel_group");InfoTile loaded=new InfoTile(legacy.getBlockPos(),legacy.getBlockState());loaded.loadWithComponents(legacyNbt,h.getLevel().registryAccess());h.assertTrue(loaded.panel.equals(PanelSettings.DEFAULT)&&PanelGroup.of(loaded).height()==1,"old cube/NBT keeps single-panel behavior");h.succeed();
    }
}
