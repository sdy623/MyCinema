package local.cinemamanager;

import local.cinemamanager.manual.ManualContent;
import local.cinemamanager.manual.ManualPagination;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

/** No game bootstrap required: verifies the boundary where font-wrapped lines become pages. */
public final class ManualChecks {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static void rejects(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException | UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(label);
    }

    public static void main(String[] args) {
        check(ManualContent.CHAPTERS.size() == 26, "tutorial chapter count");
        check(new HashSet<>(ManualContent.CHAPTERS).size() == 26, "unique chapter keys");
        for (int lineCount : new int[]{0, 1, 13, 14, 15, 27, 28, 29, 140, 141, 1000}) {
            List<String> wrapped = IntStream.range(0, lineCount).mapToObj(i -> "wrapped line " + i).toList();
            var pages = ManualPagination.paginate(List.of(new ManualPagination.Chapter<>("chapter", wrapped)), 14);
            int expected = Math.max(1, (lineCount + 13) / 14);
            check(pages.size() == expected, "font wrap boundary gives correct page count: " + lineCount);
            var restored = new ArrayList<String>();
            for (int i = 0; i < pages.size(); i++) {
                var page = pages.get(i);
                check(page.lines().size() <= 14, "no page exceeds drawable lines");
                check(page.chapterPage() == i && page.chapterPages() == expected, "continuation metadata");
                restored.addAll(page.lines());
            }
            check(restored.equals(wrapped), "no wrapped lines lost, repeated or reordered");
        }
        var longWordAndCjk = List.of("waterframes-NEOFORGE-", "mc1.21.1-v3.0.0-alpha", "中文长段落第一行", "第二行😀", "", "last line");
        var unicode = ManualPagination.paginate(List.of(new ManualPagination.Chapter<>("unicode", longWordAndCjk)), 2);
        check(unicode.stream().flatMap(p -> p.lines().stream()).toList().equals(longWordAndCjk), "wrapped long words, CJK, supplementary glyphs and blank lines preserved");

        List<ManualPagination.Chapter<Integer>> chapters = List.of(
                new ManualPagination.Chapter<>("first", IntStream.range(0, 15).boxed().toList()),
                new ManualPagination.Chapter<>("second", List.of(15)),
                new ManualPagination.Chapter<>("third", IntStream.range(16, 45).boxed().toList()));
        var pages = ManualPagination.paginate(chapters, 14);
        check(pages.size() == 6, "each chapter starts on a fresh page");
        check(ManualPagination.previousChapter(pages, 0) == 0, "previous chapter clamps at start");
        check(ManualPagination.previousChapter(pages, 1) == 0, "previous first goes to current chapter start");
        check(ManualPagination.previousChapter(pages, 2) == 0, "previous from chapter start goes to prior chapter");
        check(ManualPagination.previousChapter(pages, 3) == 2, "previous chapter with one page");
        check(ManualPagination.previousChapter(pages, 5) == 3, "previous from continuation goes to chapter start");
        check(ManualPagination.nextChapter(pages, 0) == 2, "next chapter skips all continuation pages");
        check(ManualPagination.nextChapter(pages, 1) == 2, "next from continuation");
        check(ManualPagination.nextChapter(pages, 2) == 3, "next single-page chapter");
        check(ManualPagination.nextChapter(pages, 5) == 5, "next chapter clamps at end");
        check(ManualPagination.clampPage(-1, 6) == 0, "previous page clamps");
        check(ManualPagination.clampPage(6, 6) == 5, "next page clamps");
        check(ManualPagination.clampPage(6, 0) == 0, "empty page count safe");
        check(ManualPagination.nextChapter(List.of(), 20) == 0, "empty next safe");
        check(ManualPagination.previousChapter(List.of(), 20) == 0, "empty previous safe");
        rejects(() -> ManualPagination.paginate(chapters, 0), "zero capacity rejected");
        rejects(() -> ManualPagination.paginate(chapters, -1), "negative capacity rejected");
        rejects(() -> pages.clear(), "page list immutable");
        rejects(() -> pages.getFirst().lines().clear(), "page lines immutable");
        System.out.println("PASS: " + checks + " manual checks; font-wrap page boundaries, chapter/page navigation, Unicode line preservation and immutable page snapshots.");
    }
}
