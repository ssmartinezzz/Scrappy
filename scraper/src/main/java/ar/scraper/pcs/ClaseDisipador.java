package ar.scraper.pcs;

/**
 * {@code DESCONOCIDA} is abstention ("the name didn't say") and, like
 * {@link TipoCooler#DESCONOCIDO}, is NOT a rung of the DOBLE_TORRE &gt;
 */
public enum ClaseDisipador {
    DOBLE_TORRE, TORRE, DESCONOCIDA;

    public boolean esConocida() {
        return this != DESCONOCIDA;
    }
}
