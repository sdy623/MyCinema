package local.cinemamanager;

import local.cinemamanager.panels.PanelGeometry;
import local.cinemamanager.panels.PanelGeometry.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure executable geometry checks; neither bootstraps Minecraft nor requires a graphics device. */
public final class PanelGeometryChecks {
    private static int checks;
    private static final Limits NORMAL = new Limits(16, 16, 256, 1024);
    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
    private static Set<Cell> rectangle(int x, int y, int width, int height) {
        return new HashSet<>(new Rectangle(new Cell(x, y), width, height).cells());
    }
    private static void exactCover(Set<Cell> cells, Limits limits) {
        List<Rectangle> partition = PanelGeometry.partition(cells, limits);
        Set<Cell> covered = new HashSet<>();
        for (Rectangle rectangle : partition) {
            check(rectangle.width() <= limits.width() && rectangle.height() <= limits.height()
                    && rectangle.width() * rectangle.height() <= limits.area(), "rectangle obeys all limits");
            for (Cell cell : rectangle.cells()) {
                check(cells.contains(cell), "no rectangle draws over holes or unrelated cells");
                check(covered.add(cell), "rectangle roots do not draw overlapping surfaces");
            }
        }
        check(covered.equals(cells), "every member belongs to exactly one surface");
        List<Cell> reversed = new ArrayList<>(cells);
        reversed.sort(Comparator.reverseOrder());
        check(partition.equals(PanelGeometry.partition(reversed, limits)), "input order cannot change roots or rectangles");
    }
    private static void everySeed(Set<Cell> cells, Limits limits) {
        for (Cell seed : cells) {
            Discovery component = PanelGeometry.discover(seed, cells::contains, limits.connected());
            check(!component.overflow(), "bounded fixture finishes discovery");
            List<Rectangle> partition = PanelGeometry.partition(component.cells(), limits);
            Rectangle own = partition.stream().filter(rect -> rect.contains(seed)).findFirst().orElseThrow();
            for (Cell member : own.cells()) {
                Discovery other = PanelGeometry.discover(member, cells::contains, limits.connected());
                check(component.cells().equals(other.cells()), "all member seeds discover the same connected component");
                check(partition.equals(PanelGeometry.partition(other.cells(), limits)), "all seeds reconstruct the same partition");
            }
        }
    }
    public static void main(String[] args) {
        for (int width : List.of(1, 2, 3, 16)) for (int height : List.of(1, 2, 3, 16)) {
            Set<Cell> full = rectangle(-37, -9, width, height);
            check(PanelGeometry.partition(full, NORMAL).equals(List.of(new Rectangle(new Cell(-37, -9), width, height))),
                    "complete rectangle expands both directions from an absolute bottom-left root");
            exactCover(full, NORMAL);
        }
        everySeed(rectangle(-2, -2, 2, 2), NORMAL);
        everySeed(rectangle(-2, -2, 3, 2), NORMAL);

        // Exhaust all 3x3 shapes: disconnected cells, concavities, overhangs and the central hole.
        for (int mask = 0; mask < 512; mask++) {
            Set<Cell> shape = new HashSet<>();
            for (int bit = 0; bit < 9; bit++) if ((mask & (1 << bit)) != 0)
                shape.add(new Cell(bit % 3 - 1, bit / 3 - 1));
            exactCover(shape, NORMAL);
            everySeed(shape, NORMAL);
        }

        Set<Cell> holed = rectangle(-3, -3, 7, 7);
        holed.remove(new Cell(0, 0));
        exactCover(holed, NORMAL);
        everySeed(holed, NORMAL);

        Set<Cell> cut = rectangle(0, 0, 5, 3);
        cut.removeIf(cell -> cell.x() == 2);
        Discovery left = PanelGeometry.discover(new Cell(0, 0), cut::contains, 1024);
        Discovery right = PanelGeometry.discover(new Cell(4, 2), cut::contains, 1024);
        check(left.cells().equals(rectangle(0, 0, 2, 3)) && right.cells().equals(rectangle(3, 0, 2, 3)),
                "missing strip or unavailable chunk splits the component deterministically");
        check(PanelGeometry.partition(left.cells(), NORMAL).getFirst().width() == 2, "left loaded region is independently renderable");
        check(PanelGeometry.partition(right.cells(), NORMAL).getFirst().width() == 2, "right loaded region is independently renderable");

        for (Limits limits : List.of(new Limits(3, 2, 6, 1024), new Limits(16, 16, 5, 1024),
                new Limits(1, 1, 1, 1024), NORMAL, new Limits(32, 32, 1024, 1024))) {
            exactCover(rectangle(-10, 4, 20, 20), limits);
        }
        check(PanelGeometry.partition(rectangle(0, 0, 17, 1), NORMAL).size() == 2,
                "configured side limit partitions longer rows without rejecting physical placement");
        check(PanelGeometry.partition(rectangle(0, 0, 32, 32), new Limits(32, 32, 1024, 1024)).size() == 1,
                "hard supported maximum is one complete rectangle");

        Set<Cell> huge = rectangle(0, 0, 33, 33);
        for (Cell seed : List.of(new Cell(0, 0), new Cell(16, 16), new Cell(32, 32))) {
            AtomicInteger probes = new AtomicInteger();
            Discovery discovery = PanelGeometry.discover(seed, cell -> {
                probes.incrementAndGet();
                return huge.contains(cell);
            }, 1024);
            check(discovery.overflow() && discovery.cells().equals(Set.of(seed)), "oversized components fail to symmetric singleton surfaces");
            check(probes.get() <= 4 * 1024 + 1, "component discovery has an explicit finite lookup budget");
        }
        AtomicInteger probes = new AtomicInteger();
        Discovery missing = PanelGeometry.discover(new Cell(3, 4), cell -> { probes.incrementAndGet(); return false; }, 1024);
        check(missing.cells().isEmpty() && probes.get() == 1, "unavailable seed never expands or requests neighbors");
        System.out.println("PASS: " + checks + " pure panel geometry checks; exact rectangle cover, every 3x3 shape, all member seeds, holes, splits and bounded overflow.");
    }
}
