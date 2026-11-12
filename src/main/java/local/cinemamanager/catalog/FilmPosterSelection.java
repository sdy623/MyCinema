package local.cinemamanager.catalog;

import local.cinemamanager.catalog.FilmCatalog.Film;
import java.util.function.Predicate;

/** Pure display-only choice. Does not replace a programme or grant publication permission. */
public final class FilmPosterSelection {
    private FilmPosterSelection() {}
    public static Film candidate(Film saved, Film current, Predicate<String> publicSource) {
        if (saved == null) return null;
        // An explicit public poster remains pinned even when its permission is later denied.
        if (publicSource.test(saved.poster())) return saved;
        if (current == null || current.revision() <= saved.revision()
                || !saved.id().equals(current.id()) || !saved.title().equals(current.title())
                || !saved.segments().equals(current.segments()) || !publicSource.test(current.poster())) return null;
        return current;
    }
}
