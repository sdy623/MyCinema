package local.cinemamanager.seats.fractional;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.gametest.*;

/** QA-only native API attachment measurement. No riding, render/head or release acceptance is implied. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FractionalSeatPoseProbe {
    @GameTest(template="empty",timeoutTicks=40)
    public static void fractional_original_marker_attachment_measurement(GameTestHelper h) {
        BlockPos support=h.absolutePos(new BlockPos(5,2,5));
        measure(h,support,Blocks.STONE.defaultBlockState(),1.0);
        measure(h,support,FractionalSeatHost.ANDESITE_BOTTOM_SLAB,.5);
        h.succeed();
    }
    private static void measure(GameTestHelper h,BlockPos support,BlockState state,double height) {
        var level=h.getLevel();level.setBlockAndUpdate(support,state);
        double floor=support.getY()+height;
        ServerPlayer player=new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),"FractionalPose"),ClientInformation.createDefault());
        player.setPos(support.getX()+.5,floor,support.getZ()+.5);
        ArmorStand marker=new ArmorStand(EntityType.ARMOR_STAND,level);
        CompoundTag tag=new CompoundTag();marker.saveWithoutId(tag);
        tag.putBoolean("Marker",true);tag.putBoolean("Invisible",true);tag.putBoolean("NoGravity",true);marker.load(tag);
        marker.setPos(support.getX()+.5,floor+.455,support.getZ()+.5);
        h.assertTrue(marker.isMarker(),"vanilla zero-size marker enabled");
        Vec3 point=marker.getPassengerRidingPosition(player),attachment=player.getVehicleAttachmentPoint(marker),feet=point.subtract(attachment);
        AABB body=player.getDimensions(Pose.STANDING).makeBoundingBox(feet);
        VoxelShape host=state.getCollisionShape(level,support,CollisionContext.of(player)).move(support.getX(),support.getY(),support.getZ());
        boolean overlap=Shapes.joinIsNotEmpty(Shapes.create(body.deflate(1e-7)),host,BooleanOp.AND);
        JsonObject out=new JsonObject();out.addProperty("host",state.toString());out.addProperty("floor_y",floor);
        out.addProperty("marker_floor_offset",.455);out.addProperty("marker_width",marker.getBbWidth());out.addProperty("marker_height",marker.getBbHeight());
        out.addProperty("player_width",player.getBbWidth());out.addProperty("player_height",player.getBbHeight());
        out.add("passenger_point",vector(point));out.add("player_attachment",vector(attachment));out.add("predicted_feet",vector(feet));
        out.add("full_standing_aabb",box(body));out.addProperty("predicted_feet_relative_floor",feet.y-floor);
        out.addProperty("standing_aabb_top_relative_floor",body.maxY-floor);out.addProperty("standing_eye_relative_floor",feet.y+player.getEyeHeight()-floor);
        out.addProperty("support_overlap",overlap);out.addProperty("back_top_relative_floor",1.5);
        out.addProperty("native_api_probe_executed",true);out.addProperty("actual_ride_executed",false);out.addProperty("rendered_head_measured",false);
        System.out.println("FRACTIONAL_POSE_NATIVE_API_PROBE "+out);
    }
    private static JsonArray vector(Vec3 v){JsonArray a=new JsonArray();a.add(v.x);a.add(v.y);a.add(v.z);return a;}
    private static JsonArray box(AABB b){JsonArray a=new JsonArray();for(double v:new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ})a.add(v);return a;}
    private FractionalSeatPoseProbe(){}
}
