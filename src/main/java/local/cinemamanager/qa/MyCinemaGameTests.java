package local.cinemamanager.qa;
import local.cinemamanager.automation.CinemaAutomation;

import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class MyCinemaGameTests {
    @GameTest(template="empty",timeoutTicks=40)
    public static void registered_collision_core_obeys_physics(GameTestHelper helper){
        BlockPos p=new BlockPos(3,2,3);helper.setBlock(p,CinemaManager.COLLISION_CORE.get());
        local.cinemamanager.collision.CollisionCoreTile core=helper.getBlockEntity(p);
        helper.assertTrue(core.setCoreBoxes(new int[]{0,0,0,16,8,16}),"bounded half-block core accepted");
        BlockPos world=helper.absolutePos(p);var lower=new net.minecraft.world.phys.AABB(world.getX()+.2,world.getY()+.1,world.getZ()+.2,world.getX()+.8,world.getY()+.4,world.getZ()+.8);
        var upper=new net.minecraft.world.phys.AABB(world.getX()+.2,world.getY()+.6,world.getZ()+.2,world.getX()+.8,world.getY()+.9,world.getZ()+.8);
        helper.assertFalse(helper.getLevel().noCollision(lower),"actual world physics collides inside core");helper.assertTrue(helper.getLevel().noCollision(upper),"actual world physics stays empty outside core");
        CompoundTag saved=core.saveWithoutMetadata(helper.getLevel().registryAccess());core.loadWithComponents(saved,helper.getLevel().registryAccess());helper.assertFalse(helper.getLevel().noCollision(lower),"core collision persists across NBT reload");
        helper.assertFalse(core.setCoreBoxes(new int[]{-1,0,0,16,8,16}),"bad shape rejected");helper.assertTrue(helper.getLevel().noCollision(lower),"malformed core leaves no invisible obstruction");helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void actual_output_and_save_reload(GameTestHelper helper){
        helper.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile m=helper.getBlockEntity(new BlockPos(1,1,1));m.owner=UUID.randomUUID();
        helper.setBlock(new BlockPos(8,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile t=helper.getBlockEntity(new BlockPos(8,1,1));t.owner(m.owner);
        ManagerState.Hall hall=new ManagerState.Hall();hall.terminalIdentity=t.identity().toString();hall.terminalPos=t.getBlockPos().asLong();m.state.halls.add(hall);EnrollmentRegistry.get(helper.getLevel()).claim(t.identity(),m.identity);
        var blocks=new net.minecraft.world.level.block.Block[]{CinemaManager.LIGHT.get(),CinemaManager.AISLE.get(),CinemaManager.EXIT.get(),CinemaManager.ENTRY.get()};
        for(int i=0;i<4;i++){BlockPos p=new BlockPos(2+i*2,1,4);helper.setBlock(p,blocks[i]);EndpointTile output=helper.getBlockEntity(p);output.owner=m.owner;output.manager=m.getBlockPos();output.managerIdentity=m.identity;output.hallId=hall.id;hall.outputs.add(output.getBlockPos().asLong());helper.setBlock(p.above(),Blocks.REDSTONE_LAMP);}
        ManagerState.apply(hall,ManagerState.Scene.PLAY);m.dirty();
        helper.runAfterDelay(25,()->{
            helper.assertBlockProperty(new BlockPos(2,1,4),EndpointBlock.POWER,0);helper.assertBlockProperty(new BlockPos(4,1,4),EndpointBlock.POWER,5);helper.assertBlockProperty(new BlockPos(6,1,4),EndpointBlock.POWER,15);helper.assertBlockProperty(new BlockPos(8,1,4),EndpointBlock.POWER,0);
            helper.assertBlockProperty(new BlockPos(2,2,4),RedstoneLampBlock.LIT,false);helper.assertBlockProperty(new BlockPos(4,2,4),RedstoneLampBlock.LIT,true);helper.assertBlockProperty(new BlockPos(6,2,4),RedstoneLampBlock.LIT,true);
            CompoundTag saved=m.saveWithoutMetadata(helper.getLevel().registryAccess());UUID identity=m.identity;m.loadWithComponents(saved,helper.getLevel().registryAccess());helper.assertTrue(m.identity.equals(identity),"manager identity survives real block-entity NBT");helper.assertTrue(m.state.halls.getFirst().preset==ManagerState.LightPreset.FEATURE,"preset survives NBT");
            EndpointTile exit=helper.getBlockEntity(new BlockPos(6,1,4));CompoundTag exitSaved=exit.saveWithoutMetadata(helper.getLevel().registryAccess());
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(6,2,4)),Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT,false),2);
            exit.loadWithComponents(exitSaved,helper.getLevel().registryAccess());
            helper.runAfterDelay(20,()->{
                helper.assertBlockProperty(new BlockPos(6,2,4),RedstoneLampBlock.LIT,true);
                helper.destroyBlock(new BlockPos(2,1,4));helper.assertTrue(m.state.halls.getFirst().outputs.size()==3,"broken endpoint releases capacity");
                helper.setBlock(new BlockPos(1,1,1),Blocks.AIR);
                helper.runAfterDelay(20,()->{helper.assertBlockProperty(new BlockPos(4,1,4),EndpointBlock.POWER,15);helper.assertBlockProperty(new BlockPos(6,1,4),EndpointBlock.POWER,15);helper.assertBlockProperty(new BlockPos(8,1,4),EndpointBlock.POWER,0);helper.assertBlockProperty(new BlockPos(4,2,4),RedstoneLampBlock.LIT,true);helper.assertBlockProperty(new BlockPos(6,2,4),RedstoneLampBlock.LIT,true);helper.succeed();});
            });
        });
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void persisted_grant_works_without_online_owner(GameTestHelper helper){
        helper.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile m=helper.getBlockEntity(new BlockPos(1,1,1));m.owner=UUID.randomUUID();m.state.timezone="UTC";m.state.schedulerEnabled=true;
        helper.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile t=helper.getBlockEntity(new BlockPos(4,1,1));t.owner(m.owner);
        helper.setBlock(new BlockPos(4,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());CinemaProjectorTile projector=helper.getBlockEntity(new BlockPos(4,1,4));projector.modelCore(true);projector.owner(m.owner);projector.bind(t);
        helper.setBlock(new BlockPos(6,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());CinemaProjectorTile second=helper.getBlockEntity(new BlockPos(6,1,4));second.modelCore(true);second.owner(m.owner);second.bind(t);
        ManagerState.Hall hall=new ManagerState.Hall();hall.terminalPos=t.getBlockPos().asLong();hall.terminalIdentity=t.identity().toString();m.state.halls.add(hall);EnrollmentRegistry.get(helper.getLevel()).claim(t.identity(),m.identity);
        // A persisted authorization fixture tests execution after reload, with no fake or online actor.
        CompoundTag tag=t.saveWithoutMetadata(helper.getLevel().registryAccess());ListTag links=new ListTag();CompoundTag link=new CompoundTag();link.putLong("pos",projector.getBlockPos().asLong());link.putUUID("id",projector.identity());links.add(link);CompoundTag secondLink=new CompoundTag();secondLink.putLong("pos",second.getBlockPos().asLong());secondLink.putUUID("id",second.identity());links.add(secondLink);tag.put("projectors",links);
        tag.put("theater_automation",new CinemaAutomation.Grant(t.identity(),m.identity,m.owner,m.getBlockPos(),GameType.SURVIVAL).save());t.loadWithComponents(tag,helper.getLevel().registryAccess());
        helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(m.owner)==null,"grant owner is actually offline");helper.assertTrue(t.playbackAvailable(),"reciprocal projector link exists");helper.assertTrue(projector.terminal()==t,"model-core projector resolves its enrolled terminal");helper.assertTrue(second.terminal()==t&&t.projectors().size()==2,"both model cores retain the exact reciprocal two-projector enrollment");
        String error=CinemaAutomation.execute(t,m.getBlockPos(),m.identity,m.owner,CinemaAutomation.Action.PLAY_NEW_MEDIA,"water://mycinema-offline-qa");helper.assertTrue(error==null,"offline delegated play accepted: "+error);
        helper.runAfterDelay(5,()->{
            helper.assertTrue(t.clock()!=null&&!t.data.paused&&t.clock().playing(),"WM server clock is actually playing without online owner");helper.assertTrue(projector.clock()==null&&second.clock()==null,"both model cores have no own authority clock");Object clock=t.clock();long before=t.clock().time();
            helper.runAfterDelay(15,()->{
                helper.assertTrue(t.clock().playing()&&t.clock().time()>before,"offline authority clock time advances");
                String stop=CinemaAutomation.execute(t,m.getBlockPos(),m.identity,m.owner,CinemaAutomation.Action.STOP,"");helper.assertTrue(stop==null&&t.clock().stopped(),"offline delegated stop reaches WM clock");helper.assertTrue(t.clock()==clock,"stop retains same clock object");
                t.owner(UUID.randomUUID());helper.assertTrue(t.automationGrant()==null,"ownership change revokes grant");helper.assertTrue(CinemaAutomation.execute(t,m.getBlockPos(),m.identity,m.owner,CinemaAutomation.Action.PLAY_NEW_MEDIA,"water://mycinema-offline-qa")!=null,"revoked grant cannot play");helper.succeed();
            });
        });
    }
}
