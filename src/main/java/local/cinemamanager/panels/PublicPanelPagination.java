package local.cinemamanager.panels;

import java.util.ArrayList;
import java.util.List;

/** Pagination of already font-wrapped lines, including continuations of an oversized row. */
public final class PublicPanelPagination {
    public static final int ROTATION_TICKS = 160;
    private PublicPanelPagination() {}
    public static <T> List<List<T>> paginate(List<T> lines, int linesPerPage) {
        if (linesPerPage < 1) throw new IllegalArgumentException("A page must fit one line");
        List<List<T>> pages = new ArrayList<>();
        for (int offset = 0; offset < lines.size(); offset += linesPerPage)
            pages.add(List.copyOf(lines.subList(offset, Math.min(lines.size(), offset + linesPerPage))));
        if (pages.isEmpty()) pages.add(List.of());
        return List.copyOf(pages);
    }
    public static int clampPage(int page, int count) { return Math.max(0, Math.min(page, Math.max(0, count - 1))); }
    public static int pageForRotation(long gameTime, int count) {
        return count <= 1 ? 0 : (int) Math.floorMod(Math.floorDiv(gameTime, ROTATION_TICKS), (long) count);
    }
}
