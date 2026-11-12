package local.cinemamanager.manual;

import java.util.ArrayList;
import java.util.List;

/** Paginate already-wrapped lines; the client supplies the game's actual font wrapping. */
public final class ManualPagination {
    public record Chapter<T>(String id, List<T> lines) {
        public Chapter { lines = List.copyOf(lines); }
    }
    public record Page<T>(String chapter, List<T> lines, int chapterPage, int chapterPages) {
        public Page { lines = List.copyOf(lines); }
    }

    private ManualPagination() {}

    public static <T> List<Page<T>> paginate(List<Chapter<T>> chapters, int linesPerPage) {
        if (linesPerPage < 1) throw new IllegalArgumentException("A page needs room for at least one line");
        List<Page<T>> result = new ArrayList<>();
        for (Chapter<T> chapter : chapters) {
            int count = Math.max(1, (chapter.lines().size() + linesPerPage - 1) / linesPerPage);
            for (int page = 0; page < count; page++) {
                int from = page * linesPerPage;
                int to = Math.min(chapter.lines().size(), from + linesPerPage);
                result.add(new Page<>(chapter.id(), chapter.lines().subList(from, to), page, count));
            }
        }
        return List.copyOf(result);
    }

    public static int clampPage(int page, int pageCount) {
        return Math.max(0, Math.min(page, Math.max(0, pageCount - 1)));
    }

    /** Back goes to this chapter's start before going to the previous chapter. */
    public static int previousChapter(List<? extends Page<?>> pages, int page) {
        if (pages.isEmpty()) return 0;
        int selected = clampPage(page, pages.size());
        int first = selected - pages.get(selected).chapterPage();
        if (selected > first || first == 0) return first;
        return first - 1 - pages.get(first - 1).chapterPage();
    }

    public static int nextChapter(List<? extends Page<?>> pages, int page) {
        if (pages.isEmpty()) return 0;
        int selected = clampPage(page, pages.size());
        Page<?> current = pages.get(selected);
        return clampPage(selected - current.chapterPage() + current.chapterPages(), pages.size());
    }
}
