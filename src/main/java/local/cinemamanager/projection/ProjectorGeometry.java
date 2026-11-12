package local.cinemamanager.projection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Collision generated from the same MyCinema-owned cuboids that the client
 * renders. Keeping one source of geometry prevents an invisible 3x2x3 wall
 * around the narrower cabinet artwork.
 */
public final class ProjectorGeometry {
    private static final Map<ProjectorStyle, Map<Direction, Map<BlockPos, VoxelShape>>> SHAPES = new EnumMap<>(ProjectorStyle.class);
    static {
        load(ProjectorStyle.CINEMA,"cinema_projector");
        load(ProjectorStyle.IMAX_GT,"imax_gt_projector");
    }
    private ProjectorGeometry() {}

    private static void load(ProjectorStyle style, String model) {
        String resource = "/assets/mycinema/models/block/" + model + ".json";
        try (InputStream input = ProjectorGeometry.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing projector model " + resource);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var boxes = new java.util.ArrayList<AABB>();
            for (JsonElement value : root.getAsJsonArray("elements")) {
                JsonObject element = value.getAsJsonObject();
                if (element.has("rotation") && element.getAsJsonObject("rotation").get("angle").getAsDouble() != 0)
                    throw new IllegalStateException("Projector collision cuboids must be axis aligned: " + model);
                JsonArray from = element.getAsJsonArray("from"), to = element.getAsJsonArray("to");
                boxes.add(new AABB(from.get(0).getAsDouble()/16.0,from.get(1).getAsDouble()/16.0,from.get(2).getAsDouble()/16.0,
                        to.get(0).getAsDouble()/16.0,to.get(1).getAsDouble()/16.0,to.get(2).getAsDouble()/16.0));
            }
            EnumMap<Direction,Map<BlockPos,VoxelShape>> facings = new EnumMap<>(Direction.class);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                LinkedHashMap<BlockPos,VoxelShape> cells = new LinkedHashMap<>();
                for (AABB original : boxes) {
                    AABB box = rotate(original,facing);
                    for (int x=(int)Math.floor(box.minX);x<Math.ceil(box.maxX);x++)
                        for (int y=(int)Math.floor(box.minY);y<Math.ceil(box.maxY);y++)
                            for (int z=(int)Math.floor(box.minZ);z<Math.ceil(box.maxZ);z++) {
                                BlockPos cell = new BlockPos(x,y,z);
                                VoxelShape piece = Shapes.box(Math.max(0,box.minX-x),Math.max(0,box.minY-y),Math.max(0,box.minZ-z),
                                        Math.min(1,box.maxX-x),Math.min(1,box.maxY-y),Math.min(1,box.maxZ-z));
                                cells.merge(cell,piece,Shapes::or);
                            }
                }
                cells.replaceAll((pos,shape)->shape.optimize());
                facings.put(facing,Collections.unmodifiableMap(cells));
            }
            SHAPES.put(style,facings);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot load projector model " + resource, exception);
        }
    }

    public static AABB rotate(AABB box, Direction facing) {
        return switch (facing) {
            case EAST -> new AABB(1-box.maxZ,box.minY,box.minX,1-box.minZ,box.maxY,box.maxX);
            case SOUTH -> new AABB(1-box.maxX,box.minY,1-box.maxZ,1-box.minX,box.maxY,1-box.minZ);
            case WEST -> new AABB(box.minZ,box.minY,1-box.maxX,box.maxZ,box.maxY,1-box.minX);
            default -> box;
        };
    }

    public static Map<BlockPos, VoxelShape> cells(ProjectorStyle style, Direction facing) {
        ProjectorStyle resolved = style == ProjectorStyle.STANDARD ? ProjectorStyle.CINEMA : style;
        Direction horizontal = facing != null && facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        return SHAPES.get(resolved).get(horizontal);
    }
    public static VoxelShape shape(ProjectorStyle style, Direction facing, BlockPos cell) {
        return cells(style,facing).getOrDefault(cell,Shapes.empty());
    }
    public static boolean needsFloor(BlockPos cell, VoxelShape shape) {
        return cell.getY()==0 && !shape.isEmpty() && shape.min(Direction.Axis.Y)==0;
    }
}
