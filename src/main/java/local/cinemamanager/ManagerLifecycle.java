package local.cinemamanager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.common.util.TriState;
/** Main-thread lifecycle protection. No chunk tickets and no owner-online requirement. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class ManagerLifecycle {
    @SubscribeEvent public static void toolInteraction(PlayerInteractEvent.RightClickBlock event){
        var item=event.getEntity().getItemInHand(event.getHand()).getItem();
        if(item instanceof TabletItem){
            // A normal door click must reach the same inventory credential path even when the tablet is held.
            if(!event.getLevel().hasChunkAt(event.getPos())
                    ||!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof local.cinemamanager.doors.CinemaDoorBlock))
                event.setUseBlock(TriState.FALSE);
            return;
        }
        if(!(item instanceof LinkingCard)||!event.getLevel().hasChunkAt(event.getPos()))return;
        var tile=event.getLevel().getBlockEntity(event.getPos());
        if(tile instanceof OwnedTile || tile instanceof local.cinemamanager.theater.CinemaTerminalTile)
            event.setUseBlock(TriState.FALSE); // Never overrides a protection mod's denied item use.
    }
    @SubscribeEvent public static void breakBlock(BlockEvent.BreakEvent event){
        if(!(event.getPlayer() instanceof ServerPlayer actor))return;
        var be=event.getLevel().getBlockEntity(event.getPos());
        if(!(be instanceof OwnedTile owned))return;
        if(!owned.permits(actor,8)){event.setCanceled(true);actor.displayClientMessage(Component.literal("只有设备所有者或管理员可拆除 MyCinema 设备。"),true);return;}
        if(be instanceof ManagerTile manager&&manager.pendingAutomaticStop()){
            manager.suspend("拆除前安全停止自动场次");
            if(manager.pendingAutomaticStop()){event.setCanceled(true);actor.displayClientMessage(Component.literal("影厅尚未确认停止；请先加载相关区块或恢复权限，暂不能拆除中心。"),false);}
        }
    }
}
