package ar.scraper.indices;

/**
 * A date-range multiplicative deflator: {@code precioHistorico * factor} is the
 * historical price restated in today's terms. Never returned unmarked — a
 * caller can always tell whether {@code factor} is a real ratio between two
 * observed points, a projection ({@link Confianza#EXTRAPOLADO}), or the neutral
 * fallback for an index with no data at all.
 */
public record Deflactor(double factor, Confianza confianza, int diasExtrapolados) {
    public static final Deflactor NEUTRO = new Deflactor(1.0, Confianza.SIN_DATOS, 0);
}
