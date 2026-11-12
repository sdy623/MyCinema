package local.cinemamanager.panels;

import java.util.*;
import java.util.function.Predicate;

/** Pure, bounded face-plane geometry. Coordinates increase rightward and upward on the display. */
public final class PanelGeometry {
    private PanelGeometry() {}

    public record Cell(int x, int y) implements Comparable<Cell> {
        @Override public int compareTo(Cell other) {
            int row = Integer.compare(y, other.y);
            return row == 0 ? Integer.compare(x, other.x) : row;
        }
        public List<Cell> neighbors() {
            return List.of(new Cell(x - 1, y), new Cell(x + 1, y),
                    new Cell(x, y - 1), new Cell(x, y + 1));
        }
    }

    public record Limits(int width, int height, int area, int connected) {
        public Limits {
            if (width < 1 || height < 1 || area < 1 || connected < area)
                throw new IllegalArgumentException("Invalid panel geometry limits");
        }
    }

    public record Discovery(Set<Cell> cells, boolean overflow) {
        public Discovery { cells = Set.copyOf(cells); }
    }

    public record Rectangle(Cell root, int width, int height) {
        public Rectangle {
            Objects.requireNonNull(root);
            if (width < 1 || height < 1) throw new IllegalArgumentException("Empty panel rectangle");
        }
        public boolean contains(Cell cell) {
            return cell.x >= root.x && cell.x < (long) root.x + width
                    && cell.y >= root.y && cell.y < (long) root.y + height;
        }
        public List<Cell> cells() {
            List<Cell> out = new ArrayList<>(width * height);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) out.add(new Cell(root.x + x, root.y + y));
            return List.copyOf(out);
        }
    }

    /**
     * The predicate must be a loaded-only, transitive compatibility test against the seed.
     * An over-budget component becomes a singleton from every seed, never a seed-dependent prefix.
     * At most 4 * limit + 1 distinct positions are probed before an overflow is known.
     */
    public static Discovery discover(Cell seed, Predicate<Cell> matches, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid component limit");
        Set<Cell> visited = new HashSet<>();
        Set<Cell> cells = new HashSet<>();
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        visited.add(seed);
        if (!matches.test(seed)) return new Discovery(Set.of(), false);
        cells.add(seed);
        pending.add(seed);
        while (!pending.isEmpty()) {
            for (Cell next : pending.removeFirst().neighbors()) {
                if (!visited.add(next) || !matches.test(next)) continue;
                if (cells.size() == limit) return new Discovery(Set.of(seed), true);
                cells.add(next);
                pending.addLast(next);
            }
        }
        return new Discovery(cells, false);
    }

    /**
     * Deterministic exact cover, anchored at the lowest row's leftmost remaining tile.
     * Choose the largest filled rectangle there; ties prefer width. Holes are never covered.
     * This is deliberately a stable greedy cover, not an expensive minimum-rectangle solver.
     */
    public static List<Rectangle> partition(Collection<Cell> cells, Limits limits) {
        if (cells.size() > limits.connected) throw new IllegalArgumentException("Unbounded panel component");
        NavigableSet<Cell> remaining = new TreeSet<>(cells);
        List<Rectangle> rectangles = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Cell root = remaining.first();
            int commonWidth = limits.width, bestWidth = 1, bestHeight = 1, bestArea = 1;
            for (int height = 1; height <= limits.height && height <= limits.area; height++) {
                int rowWidth = 0;
                while (rowWidth < commonWidth && remaining.contains(new Cell(root.x + rowWidth, root.y + height - 1)))
                    rowWidth++;
                commonWidth = rowWidth;
                if (commonWidth == 0) break;
                int width = Math.min(commonWidth, limits.area / height);
                int area = width * height;
                if (area > bestArea || area == bestArea && width > bestWidth) {
                    bestArea = area;
                    bestWidth = width;
                    bestHeight = height;
                }
            }
            Rectangle rectangle = new Rectangle(root, bestWidth, bestHeight);
            remaining.removeAll(rectangle.cells());
            rectangles.add(rectangle);
        }
        return List.copyOf(rectangles);
    }
}
