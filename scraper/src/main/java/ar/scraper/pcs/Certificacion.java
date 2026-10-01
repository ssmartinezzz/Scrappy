package ar.scraper.pcs;

/**
 * {@code NINGUNA} is the bottom rung of the real scale (no certification stated), not an abstention
 * outside it — unlike {@link Gama#DESCONOCIDA}, it participates in ordinal comparisons via
 * {@link #alcanza}.
 */
public enum Certificacion {
    NINGUNA, WHITE, BRONZE, SILVER, GOLD, PLATINUM, TITANIUM;

    public boolean alcanza(Certificacion minimo) {
        return this.ordinal() >= minimo.ordinal();
    }
}
