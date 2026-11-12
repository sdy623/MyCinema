package local.cinemamanager.client.seats;

import com.google.gson.GsonBuilder;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.seats.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Opt-in native-client sample. No result exists until a real client runs /mycinemaseatqa. */
@EventBusSubscriber(modid=CinemaManager.ID,value=Dist.CLIENT)
public final class CinemaSeatClientProbe {
    private static int pendingTicks;
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        if (Boolean.getBoolean("mycinema.qa")) event.getDispatcher().register(Commands.literal("mycinemaseatqa")
            .executes(context->{pendingTicks=4; return 1;}));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (pendingTicks<=0 || --pendingTicks!=0) return;
        Minecraft mc=Minecraft.getInstance();
        if (mc.player==null || mc.level==null) return;
        var player=mc.player;
        CinemaSeatMount mount=player.getVehicle() instanceof CinemaSeatMount seatMount ? seatMount : null;
        BlockPos pos=mount!=null ? mount.blockPosition() : mc.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : null;
        CinemaSeatTile tile=pos==null ? null : CinemaSeatBlock.findSeat(mc.level,pos).orElse(null);
        Map<String,Object> result=new LinkedHashMap<>();
        String stamp=Long.toString(System.currentTimeMillis());
        result.put("sample_utc",Instant.now().toString());
        result.put("source","real_native_client_command");
        result.put("dimension",mc.level.dimension().location().toString());
        result.put("camera_mode",mc.options.getCameraType().name());
        result.put("player_pose",player.getPose().name());
        result.put("mounted_on_physical_chair",mount!=null && tile!=null);
        result.put("player_feet_world",vector(player.position()));
        result.put("player_eye_y_world",player.getEyeY());
        result.put("camera_world",vector(mc.gameRenderer.getMainCamera().getPosition()));
        result.put("player_bounding_box_top_world",player.getBoundingBox().maxY);
        result.put("rendered_head_top_world",null);
        result.put("head_top_note","Bounding-box top is not a measured rendered head. Verify the native third-person screenshot before sightline acceptance.");
        if (tile!=null) {
            result.put("seat_position",List.of(pos.getX(),pos.getY(),pos.getZ()));
            result.put("seat_uid",tile.seatUid().toString());
            result.put("seat_facing",tile.getBlockState().getValue(CinemaSeatBlock.FACING).getName());
            result.put("player_feet_above_floor",player.getY()-pos.getY());
            result.put("sit_eye_y_above_floor",mount==null?null:player.getEyeY()-pos.getY());
        }
        if (mount!=null) {
            result.put("mount_entity_position",vector(mount.position()));
            result.put("passenger_riding_position",vector(mount.getPassengerRidingPosition(player)));
            result.put("passenger_vehicle_attachment",vector(player.getVehicleAttachmentPoint(mount)));
            result.put("passenger_attachment_offset",vector(mount.getPassengerRidingPosition(player).subtract(mount.position())));
        }
        String screenshot="cinema-seat-qa-"+stamp+".png";
        result.put("requested_screenshot","screenshots/"+screenshot);
        result.put("screenshot_exists_at_report_write",false);
        result.put("appearance_accepted",false);
        try {
            Path dir=mc.gameDirectory.toPath().resolve("cinema-seat-qa");
            Files.createDirectories(dir);
            Path report=dir.resolve("pose-"+stamp+".json");
            Files.writeString(report,new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(result));
            Screenshot.grab(mc.gameDirectory,screenshot,mc.getMainRenderTarget(),message->mc.execute(()->player.displayClientMessage(message,false)));
            player.displayClientMessage(Component.literal("Cinema seat native pose saved: "+report.getFileName()),false);
        } catch (Exception error) {
            player.displayClientMessage(Component.literal("Cinema seat QA save failed: "+error.getMessage()),false);
        }
    }
    private static List<Double> vector(Vec3 value) { return List.of(value.x,value.y,value.z); }
    private CinemaSeatClientProbe() {}
}
