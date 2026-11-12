package local.cinemamanager.collision;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Immutable, fail-closed input for one block's collision. No Minecraft dependency. */
public final class CoreShapeData {
    public static final int VERSION = 1;
    public static final int RESOLUTION = 16;
    public static final int MAX_BOXES = 512;
    public static final int MAX_PACKED_INTS = MAX_BOXES * 6;
    public static final int MAX_PAYLOAD_BYTES = MAX_PACKED_INTS * Integer.BYTES;
    public static final int MAX_VOXELS = RESOLUTION * RESOLUTION * RESOLUTION;
    public static final CoreShapeData EMPTY = new CoreShapeData(new int[0], List.of(), new BitSet());

    /** Coordinates are block-local; the factory enforces finite, exact 1/16 values. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }

    private final int[] packed;
    private final List<Box> boxes;
    private final BitSet occupied;

    private CoreShapeData(int[] packed, List<Box> boxes, BitSet occupied) {
        this.packed = packed;
        this.boxes = List.copyOf(boxes);
        this.occupied = occupied;
    }

    public static CoreShapeData fromPacked(int version, int resolution, int[] input) {
        if (version != VERSION || resolution != RESOLUTION) throw invalid("Unsupported core schema");
        if (input == null || input.length > MAX_PACKED_INTS || input.length % 6 != 0)
            throw invalid("Invalid packed box count");
        if (input.length == 0) return EMPTY;
        // Copy before validating: callers cannot change accepted data after validation.
        int[] packed = input.clone();
        List<Box> boxes = new ArrayList<>(packed.length / 6);
        BitSet occupied = new BitSet(MAX_VOXELS);
        for (int i = 0; i < packed.length; i += 6) {
            for (int a = 0; a < 6; a++) if (packed[i + a] < 0 || packed[i + a] > RESOLUTION)
                throw invalid("Box outside local block");
            for (int a = 0; a < 3; a++) if (packed[i + a] >= packed[i + a + 3])
                throw invalid("Empty or inverted box");
            for (int x = packed[i]; x < packed[i + 3]; x++)
                for (int y = packed[i + 1]; y < packed[i + 4]; y++)
                    for (int z = packed[i + 2]; z < packed[i + 5]; z++) {
                        int index = index(x, y, z);
                        if (occupied.get(index)) throw invalid("Overlapping or duplicate boxes");
                        occupied.set(index);
                    }
            boxes.add(new Box(packed[i] / 16.0, packed[i + 1] / 16.0, packed[i + 2] / 16.0,
                    packed[i + 3] / 16.0, packed[i + 4] / 16.0, packed[i + 5] / 16.0));
        }
        return new CoreShapeData(packed, boxes, occupied);
    }

    public static CoreShapeData fromBoxes(List<Box> input) {
        if (input == null || input.size() > MAX_BOXES) throw invalid("Invalid box count");
        int[] packed = new int[input.size() * 6];
        int i = 0;
        for (Box b : input) {
            if (b == null) throw invalid("Null box");
            for (double value : new double[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) {
                if (!Double.isFinite(value) || value < 0 || value > 1 || value * RESOLUTION != Math.rint(value * RESOLUTION))
                    throw invalid("Non-finite, out-of-bounds or off-grid coordinate");
                packed[i++] = (int) (value * RESOLUTION);
            }
        }
        return fromPacked(VERSION, RESOLUTION, packed);
    }

    public int[] packedCopy() { return packed.clone(); }
    public List<Box> boxes() { return boxes; }
    public int voxelCount() { return occupied.cardinality(); }
    public boolean isEmpty() { return boxes.isEmpty(); }
    public boolean occupied(int x, int y, int z) {
        return x >= 0 && x < RESOLUTION && y >= 0 && y < RESOLUTION && z >= 0 && z < RESOLUTION
                && occupied.get(index(x, y, z));
    }
    private static int index(int x, int y, int z) { return (x * RESOLUTION + y) * RESOLUTION + z; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
