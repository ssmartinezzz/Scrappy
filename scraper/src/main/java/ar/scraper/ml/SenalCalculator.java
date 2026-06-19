package ar.scraper.ml;

import ar.scraper.db.DatabaseService.HistorialEntry;
import ar.scraper.model.Product.SenalCompra;

import java.util.Comparator;
import java.util.List;

/**
 * Pure, dependency-free buy-signal classifier. Lifted from the inline logic
 * that previously lived in {@code ApiController.recomendacion} (lines
 * 588-652), so it can be precomputed once per scrape run (mirroring the
 * {@code MlEnricher}/{@code MlScore} pattern) instead of being recalculated
 * on every live request.
 *
 * <p>No DB/Spring/service dependencies: inflation adjustment is taken as an
 * already-resolved multiplicative factor ({@code precioAjustado = precioAntiguo
 * * inflacionFactor}), so this class stays trivially unit-testable.</p>
 */
public final class SenalCalculator {

    private static final double PCT_DEL_MIN_COMPRAR_AHORA = 10.0;
    private static final double CAMBIO_REAL_MUY_BUEN_MOMENTO = -8.0;
    private static final double CAMBIO_REAL_BUEN_MOMENTO = -3.0;
    private static final double CAMBIO_REAL_ESPERAR = 10.0;
    private static final double PCT_DEL_MIN_CARO = 80.0;
    private static final double TENDENCIA_UMBRAL = 5.0;

    private static final int SCORE_COMPRAR_AHORA = 95;
    private static final int SCORE_MUY_BUEN_MOMENTO = 85;
    private static final int SCORE_BUEN_MOMENTO = 70;
    private static final int SCORE_ESPERAR = 20;
    private static final int SCORE_CARO = 15;
    private static final int SCORE_PRECIO_NORMAL = 50;

    private SenalCalculator() {
    }

    /**
     * Classifies a product's buy signal from its price history.
     *
     * @param historial       price history points (any order; sorted internally by {@code fecha})
     * @param inflacionFactor multiplicative inflation adjustment already resolved by the caller
     *                        (e.g. {@code vHoy / vEntonces} or a compounded monthly rate),
     *                        applied as {@code precioAntiguo * inflacionFactor}
     * @return the resolved {@link SenalCompra}; {@link SenalCompra#EMPTY} when there is no
     *         usable history
     */
    public static SenalCompra compute(List<HistorialEntry> historial, double inflacionFactor) {
        if (historial == null || historial.isEmpty()) {
            return SenalCompra.EMPTY;
        }

        List<HistorialEntry> sorted = historial.stream()
                .sorted(Comparator.comparing(HistorialEntry::fecha))
                .toList();

        double precioActual = sorted.get(sorted.size() - 1).precio();
        double precioMin = sorted.stream().mapToDouble(HistorialEntry::precio).min().orElse(precioActual);
        double precioMax = sorted.stream().mapToDouble(HistorialEntry::precio).max().orElse(precioActual);
        double rango = precioMax - precioMin;

        int puntoAntiguo = Math.max(0, sorted.size() - 13);
        double precioAntiguo = sorted.get(puntoAntiguo).precio();
        double precioAjustado = precioAntiguo * inflacionFactor;
        double cambioReal = precioAjustado > 0
                ? (precioActual - precioAjustado) / precioAjustado * 100.0 : 0.0;
        double pctDelMin = rango > 0 ? (precioActual - precioMin) / rango * 100.0 : 50.0;

        String tendencia = "estable";
        if (sorted.size() >= 4) {
            double p1 = sorted.get(sorted.size() - 4).precio();
            double p2 = sorted.get(sorted.size() - 1).precio();
            double cambioNominal = p1 > 0 ? (p2 - p1) / p1 * 100.0 : 0;
            if (cambioNominal > TENDENCIA_UMBRAL) tendencia = "subiendo";
            else if (cambioNominal < -TENDENCIA_UMBRAL) tendencia = "bajando";
        }

        if (pctDelMin <= PCT_DEL_MIN_COMPRAR_AHORA) {
            return new SenalCompra("comprar_ahora", SCORE_COMPRAR_AHORA);
        }
        if (cambioReal < CAMBIO_REAL_MUY_BUEN_MOMENTO && "bajando".equals(tendencia)) {
            return new SenalCompra("muy_buen_momento", SCORE_MUY_BUEN_MOMENTO);
        }
        if (cambioReal < CAMBIO_REAL_BUEN_MOMENTO) {
            return new SenalCompra("buen_momento", SCORE_BUEN_MOMENTO);
        }
        if (cambioReal > CAMBIO_REAL_ESPERAR && "subiendo".equals(tendencia)) {
            return new SenalCompra("esperar", SCORE_ESPERAR);
        }
        if (pctDelMin >= PCT_DEL_MIN_CARO) {
            return new SenalCompra("caro", SCORE_CARO);
        }
        return new SenalCompra("precio_normal", SCORE_PRECIO_NORMAL);
    }
}
