package ar.scraper.pcs;

/**
 * {@code DESCONOCIDO} is abstention ("the name didn't parse to a known technology") and, like
 * {@link TipoAlmacenamiento#DESCONOCIDO}, is NOT a rung of the LIQUIDO &gt;
 */
public enum TipoCooler {
    LIQUIDO, AIRE, DESCONOCIDO;

    public boolean esConocido() {
        return this != DESCONOCIDO;
    }
}
