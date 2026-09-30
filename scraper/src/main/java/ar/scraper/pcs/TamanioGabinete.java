package ar.scraper.pcs;

/**
 * {@code DESCONOCIDO} is abstention ("the name didn't say") and, like
 * {@link TipoCooler#DESCONOCIDO}, is NOT a rung of the MINI &lt; {@link #esConocido()} models that
 * explicitly instead of leaning on the enum's declaration order.
 */
public enum TamanioGabinete {
    MINI, MID, FULL, DESCONOCIDO;

    public boolean esConocido() {
        return this != DESCONOCIDO;
    }
}
