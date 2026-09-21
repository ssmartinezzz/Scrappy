package ar.scraper.pcs;

/**
 * Storage technology read off an Almacenamiento part's name. {@code
 * DESCONOCIDO} is abstention ("the name didn't parse to a known
 * technology") and, like {@link Gama#DESCONOCIDA}, is NOT a rung of the
 * NVME &gt; SSD &gt; HDD scale — modelled explicitly with {@link
 * #esConocido()} instead of relying on enum declaration order, same
 * reasoning as {@link Gama}'s javadoc: Java's implicit ordinal would place
 * {@code DESCONOCIDO} above or below the other three depending on where
 * it's declared, which is not a claim this type makes.
 *
 * <p>Pendrives and micro SD cards land here too (measured: 30/290
 * Almacenamiento rows in the catalog have no readable technology — see
 * CLAUDE.md, "Armador de PCs"). They are not vetoed anywhere: abstention is
 * the last rung of any technical-quality axis, so they sink to the bottom
 * of the ranking on their own instead.</p>
 */
public enum TipoAlmacenamiento {
    NVME, SSD, HDD, DESCONOCIDO;

    public boolean esConocido() {
        return this != DESCONOCIDO;
    }
}
