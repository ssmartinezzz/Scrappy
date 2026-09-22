package ar.scraper.pcs;

/**
 * Cooling technology read off a Cooler part's name. {@code DESCONOCIDO} is
 * abstention ("the name didn't parse to a known technology") and, like
 * {@link TipoAlmacenamiento#DESCONOCIDO}, is NOT a rung of the LIQUIDO &gt;
 * AIRE scale — modelled explicitly with {@link #esConocido()} instead of
 * relying on enum declaration order, same reasoning as {@link Gama}'s
 * javadoc.
 *
 * <p>Paste/cleaner/pad products (thermal paste, cleaning cloths) land here
 * too, same as pendrives inside {@link TipoAlmacenamiento}: they are not
 * vetoed anywhere, abstention is the last rung of the axis, so they sink to
 * the bottom of the cooler ranking on their own instead of a dedicated
 * filter (pc-builder-deep-taxonomy, T4d-2).</p>
 */
public enum TipoCooler {
    LIQUIDO, AIRE, DESCONOCIDO;

    public boolean esConocido() {
        return this != DESCONOCIDO;
    }
}
