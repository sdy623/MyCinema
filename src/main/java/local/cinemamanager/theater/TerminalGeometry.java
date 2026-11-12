package local.cinemamanager.theater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Collision and selection generated from the same MyCinema TMS model that is rendered. */
public final class TerminalGeometry {
    private static final Map<Direction, VoxelShape> SHAPES = load();

    private TerminalGeometry() {}

    private static Map<Direction, VoxelShape> load() {
        String resource = "/assets/mycinema/models/block/tms_terminal.json";
        try (InputStream input = TerminalGeometry.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing TMS model " + resource);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var boxes = new java.util.ArrayList<AABB>();
            for (JsonElement value : root.getAsJsonArray("elements")) {
                JsonObject element = value.getAsJsonObject();
                if (element.has("rotation") && element.getAsJsonObject("rotation").get("angle").getAsDouble() != 0)
                    throw new IllegalStateException("TMS collision cuboids must be axis aligned");
                JsonArray from = element.getAsJsonArray("from"), to = element.getAsJsonArray("to");
                boxes.add(new AABB(from.get(0).getAsDouble() / 16.0, from.get(1).getAsDouble() / 16.0,
                        from.get(2).getAsDouble() / 16.0, to.get(0).getAsDouble() / 16.0,
                        to.get(1).getAsDouble() / 16.0, to.get(2).getAsDouble() / 16.0));
            }
            EnumMap<Direction, VoxelShape> result = new EnumMap<>(Direction.class);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape shape = Shapes.empty();
                for (AABB box : boxes) shape = Shapes.or(shape, Shapes.create(rotate(box, facing)));
                result.put(facing, shape.optimize());
            }
            return Map.copyOf(result);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot load TMS model " + resource, exception);
        }
    }

    private static AABB rotate(AABB box, Direction facing) {
        return switch (facing) {
            case EAST -> new AABB(1 - box.maxZ, box.minY, box.minX, 1 - box.minZ, box.maxY, box.maxX);
            case SOUTH -> new AABB(1 - box.maxX, box.minY, 1 - box.maxZ, 1 - box.minX, box.maxY, 1 - box.minZ);
            case WEST -> new AABB(box.minZ, box.minY, 1 - box.maxX, box.maxZ, box.maxY, 1 - box.minX);
            default -> box;
        };
    }

    public static VoxelShape shape(Direction facing) {
        Direction horizontal = facing != null && facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        return SHAPES.get(horizontal);
    }
}
