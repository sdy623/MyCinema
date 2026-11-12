package local.cinemamanager.seats;

import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid=CinemaManager.ID)
public final class CinemaSeatEvents {
    private static final String SESSION="mycinema_seat_session";
    @SubscribeEvent public static void mount(EntityMountEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntityBeingMounted() instanceof CinemaSeatMount mount)) return;
        if (event.isMounting()) {
            if (!mount.accepts(event.getEntityMounting()) || !mount.getPassengers().isEmpty()) event.setCanceled(true);
        } else if (!mount.releasing() && !mount.isRemoved() && event.getEntityMounting() instanceof LivingEntity passenger) {
            boolean safe=mount.prepareDismount(passenger);
            if (!safe && passenger.isAlive() && !passenger.isRemoved()) {
                event.setCanceled(true);
                // A client may have predicted Shift-dismount. Reassert server occupancy after a rejected exit.
                if (passenger instanceof ServerPlayer player) player.connection.send(new ClientboundSetPassengersPacket(mount));
            }
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().getVehicle() instanceof CinemaSeatMount mount) mount.releaseAndDiscard();
    }
    static void rememberSession(ServerPlayer player,CinemaSeatMount mount) {
        CompoundTag session=new CompoundTag();
        session.putLong("position",mount.seatPos().asLong());
        session.putUUID("seat_uid",mount.seatUid());
        session.putString("dimension",player.level().dimension().location().toString());
        session.putString("facing",mount.facing().getName());
        player.getPersistentData().put(SESSION,session);
    }
    static void clearSession(ServerPlayer player) { player.getPersistentData().remove(SESSION); }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.getPersistentData().contains(SESSION)) return;
        CompoundTag session=player.getPersistentData().getCompound(SESSION);
        if (player.isPassenger() || !session.hasUUID("seat_uid") || !session.contains("position")
            || !player.level().dimension().location().toString().equals(session.getString("dimension"))) {
            clearSession(player); return;
        }
        BlockPos pos=BlockPos.of(session.getLong("position"));
        Direction facing=Direction.byName(session.getString("facing"));
        if (facing==null || !facing.getAxis().isHorizontal() || player.position().distanceToSqr(pos.getCenter())>16) {
            clearSession(player); return;
        }
        if (!player.level().hasChunkAt(pos)) { CinemaSeatTile.feedback(player,"chair_no_exit"); return; }
        // This scratch object is never added to the level or ticked. The former vehicle is not revived.
        CinemaSeatMount probe=new CinemaSeatMount(SeatsModule.CHAIR_MOUNT.get(),player.level());
        probe.initializeRecovery(pos,session.getUUID("seat_uid"),facing,player);
        var exit=probe.safeExit(player);
        if (exit.isPresent()) {
            var point=exit.orElseThrow();
            player.teleportTo(point.x,point.y,point.z);
            clearSession(player);
        } else CinemaSeatTile.feedback(player,"chair_no_exit");
    }
    private CinemaSeatEvents() {}
}
