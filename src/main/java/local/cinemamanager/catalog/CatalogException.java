package local.cinemamanager.catalog;

/** A stable, translatable failure key. Rejected mutations leave the catalog unchanged. */
public final class CatalogException extends IllegalArgumentException {
    public CatalogException(String suffix) { super("mycinema.catalog." + suffix); }
    public CatalogException(String suffix, Throwable cause) { super("mycinema.catalog." + suffix, cause); }
}
