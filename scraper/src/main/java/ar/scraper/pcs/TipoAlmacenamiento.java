package ar.scraper.pcs;

/**
 * {@code DESCONOCIDO} is abstention ("the name didn't parse to a known technology") and, like
 * {@link Gama#DESCONOCIDA}, is NOT a rung of the NVME &gt;
 */
public enum TipoAlmacenamiento {
    NVME, SSD, HDD, DESCONOCIDO;

    public boolean esConocido() {
        return this != DESCONOCIDO;
    }
}
