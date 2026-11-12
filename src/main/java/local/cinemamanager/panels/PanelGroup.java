package local.cinemamanager.panels;

import local.cinemamanager.InfoBlock;
import local.cinemamanager.InfoTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntSupplier;

/** A fully filled rectangular surface within one loaded, compatible face-plane component. */
public record PanelGroup(List<InfoTile> members) {
    public static final int DEFAULT_MAX_SIDE = 16, DEFAULT_MAX_AREA = 256;
    /** Protocol/safety ceilings, independent of a client's local server configuration. */
    public static final int MAX_WIDTH = 32, MAX_HEIGHT = 32, MAX_AREA = 1024, MAX_CONNECTED = 1024;
    private static volatile IntSupplier sideLimit = () -> DEFAULT_MAX_SIDE;
    private static volatile IntSupplier areaLimit = () -> DEFAULT_MAX_AREA;

    public PanelGroup {
        if (members == null || members.isEmpty() || members.size() > MAX_AREA)
            throw new IllegalArgumentException("Invalid panel group size");
        List<InfoTile> sorted = new ArrayList<>(members);
        Direction right = right(sorted.getFirst());
        sorted.sort(Comparator.comparingInt((InfoTile t) -> t.getBlockPos().getY())
                .thenComparingInt(t -> horizontal(t.getBlockPos(), right)));
        members = List.copyOf(sorted);
        InfoTile root = members.getFirst();
        Set<BlockPos> occupied = new HashSet<>();
        int minX = horizontal(root.getBlockPos(), right), maxX = minX, maxY = root.getBlockPos().getY();
        for (InfoTile member : members) {
            BlockPos delta = member.getBlockPos().subtract(root.getBlockPos());
            int x = horizontal(member.getBlockPos(), right);
            if (!occupied.add(member.getBlockPos()) || delta.getX() * right.getStepZ() != delta.getZ() * right.getStepX()
                    || facing(member) != facing(root) || x < minX)
                throw new IllegalArgumentException("Invalid panel plane");
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, member.getBlockPos().getY());
        }
        int width = maxX - minX + 1;
        int height = maxY - root.getBlockPos().getY() + 1;
        if (width < 1 || width > MAX_WIDTH || height > MAX_HEIGHT || width * height != members.size())
            throw new IllegalArgumentException("Panel group must be a filled bounded rectangle");
    }

    /** Register common-config readers once; values are read on demand and clamped to protocol ceilings. */
    public static void configureLimits(IntSupplier side, IntSupplier area) {
        sideLimit = Objects.requireNonNull(side);
        areaLimit = Objects.requireNonNull(area);
    }
    public static int maxSide() { return Math.clamp(sideLimit.getAsInt(), 1, MAX_WIDTH); }
    public static int maxArea() { return Math.clamp(areaLimit.getAsInt(), 1, MAX_AREA); }
    private static PanelGeometry.Limits limits(boolean publicTags) {
        int side = publicTags ? MAX_WIDTH : maxSide();
        return new PanelGeometry.Limits(side, side, publicTags ? MAX_AREA : maxArea(), MAX_CONNECTED);
    }

    public InfoTile root() { return members.getFirst(); }
    public int width() {
        Direction right = right(root());
        return members.stream().mapToInt(t -> horizontal(t.getBlockPos(), right)).max().orElseThrow()
                - horizontal(root().getBlockPos(), right) + 1;
    }
    public int height() { return members.getLast().getBlockPos().getY() - root().getBlockPos().getY() + 1; }
    /** Local renderer +X after -facing.toYRot(), including north/east's negative world direction. */
    public static Direction right(InfoTile tile) { return facing(tile).getCounterClockWise(); }
    private static Direction facing(InfoTile tile) { return tile.getBlockState().getValue(InfoBlock.FACING); }
    private static int horizontal(BlockPos pos, Direction right) {
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
    }
    private static PanelGeometry.Cell cell(BlockPos pos, Direction right) {
        return new PanelGeometry.Cell(horizontal(pos, right), pos.getY());
    }

    /** Rebuilt from current membership, never from the saved group UUID or a cached editor snapshot. */
    public String signature() {
        StringBuilder value = new StringBuilder("panel-plane-v2:").append(facing(root())).append(':')
                .append(width()).append('x').append(height()).append(';');
        for (InfoTile tile : members) value.append(tile.getBlockPos().asLong()).append(':')
                .append(tile.identity).append(':').append(tile.panelRevision).append(';');
        return UUID.nameUUIDFromBytes(value.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean sameFace(InfoTile a, InfoTile b) {
        return a != null && b != null && !a.isRemoved() && !b.isRemoved()
                && a.getBlockState().getBlock() instanceof WallInfoBlock
                && b.getBlockState().getBlock() instanceof WallInfoBlock && facing(a) == facing(b);
    }
    private static boolean sameDisplay(InfoTile a, InfoTile b) {
        return sameFace(a, b) && a.panel.equals(b.panel) && a.scope == b.scope && a.terminalRole.equals(b.terminalRole) && a.invalidTerminalRole()==b.invalidTerminalRole();
    }
    public static boolean compatible(InfoTile a, InfoTile b) {
        if (!sameDisplay(a, b)) return false;
        if (a.getLevel() != null && a.getLevel().isClientSide) return visualCompatible(a, b);
        // Saved UUIDs and per-tile poster grants are retained when separately placed groups meet.
        return a.owner != null && a.owner.equals(b.owner) && Objects.equals(a.manager, b.manager)
                && Objects.equals(a.managerIdentity, b.managerIdentity) && Objects.equals(a.hallId, b.hallId);
    }
    /** Public tags carry the server rectangle's opaque signature instead of its disk-only UUID. */
    public static boolean visualCompatible(InfoTile a, InfoTile b) {
        return sameDisplay(a, b) && a.panelGroup != null && a.panelGroup.equals(b.panelGroup);
    }
    public static InfoTile loaded(Level level, BlockPos pos) {
        if (level == null || !level.hasChunkAt(pos)) return null;
        return level.getBlockEntity(pos) instanceof InfoTile tile && !tile.isRemoved() ? tile : null;
    }

    /** Deterministic source for a freshly placed tile; it never changes an existing neighbor. */
    public static InfoTile placementSource(InfoTile added) {
        if (added.getLevel() == null || added.getLevel().isClientSide || added.owner == null
                || !(added.getBlockState().getBlock() instanceof WallInfoBlock)) return null;
        Direction right = right(added);
        return adjacent(added).stream().map(pos -> loaded(added.getLevel(), pos))
                .filter(tile -> sameFace(added, tile) && added.owner.equals(tile.owner))
                .min(Comparator.comparingInt((InfoTile tile) -> tile.getBlockPos().getY())
                        .thenComparingInt(tile -> horizontal(tile.getBlockPos(), right))).orElse(null);
    }
    public static List<BlockPos> adjacent(InfoTile tile) {
        BlockPos pos = tile.getBlockPos();
        Direction right = right(tile);
        return List.of(pos.below(), pos.relative(right.getOpposite()), pos.relative(right), pos.above());
    }

    public static PanelGroup of(InfoTile tile) {
        Level level = tile.getLevel();
        if (level == null || !(tile.getBlockState().getBlock() instanceof WallInfoBlock))
            return new PanelGroup(List.of(tile));
        return of(tile, pos -> loaded(level, pos), level.isClientSide);
    }

    /** Loaded-only lookup seam for public-tag reconstruction and partial-chunk lifecycle checks. */
    public static PanelGroup of(InfoTile tile, Function<BlockPos, InfoTile> loadedLookup, boolean publicTags) {
        if (!(tile.getBlockState().getBlock() instanceof WallInfoBlock)) return new PanelGroup(List.of(tile));
        Direction right = right(tile);
        BlockPos origin = tile.getBlockPos();
        PanelGeometry.Cell seed = cell(origin, right);
        Map<PanelGeometry.Cell, InfoTile> found = new HashMap<>();
        PanelGeometry.Discovery component = PanelGeometry.discover(seed, point -> {
            BlockPos pos = origin.relative(right, point.x() - seed.x()).offset(0, point.y() - seed.y(), 0);
            InfoTile next = loadedLookup.apply(pos);
            // Detached NBT copies cannot borrow membership or poster authority from the live tile.
            if (point.equals(seed) && next != tile) return false;
            if (next == null || !(publicTags ? visualCompatible(tile, next) : compatible(tile, next))) return false;
            found.put(point, next);
            return true;
        }, MAX_CONNECTED);
        if (component.overflow() || component.cells().isEmpty()) return new PanelGroup(List.of(tile));
        for (PanelGeometry.Rectangle rectangle : PanelGeometry.partition(component.cells(), limits(publicTags))) {
            if (rectangle.contains(seed)) return new PanelGroup(rectangle.cells().stream().map(found::get).toList());
        }
        return new PanelGroup(List.of(tile));
    }
}
