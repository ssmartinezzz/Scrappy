package ar.scraper.ml;

import ar.scraper.model.Product.SenalFinanciacion;

/**
 * Mirrors {@link SenalCalculator}'s static-pure style: no Spring/DB dependencies, named threshold
 * constants, trivially unit-testable.
 */
public final class FinanciacionCalculator {

    private static final double AHORRO_REAL_CONVIENE_CUOTAS = 5.0;

    private static final double AHORRO_REAL_CONVIENE_CONTADO = -5.0;

    private FinanciacionCalculator() {
    }

    public static SenalFinanciacion compute(double precioContado, double recargoPct, int cuotas, double iMensual) {
        if (precioContado <= 0 || cuotas <= 0 || recargoPct <= -100 || iMensual <= -1.0) {
            return SenalFinanciacion.EMPTY;
        }

        double precioCuotas = precioContado * (1 + recargoPct / 100.0);
        double cuota = precioCuotas / cuotas;

        double vp = 0.0;
        for (int k = 1; k <= cuotas; k++) {
            vp += cuota / Math.pow(1 + iMensual, k);
        }

        double ahorroReal = (precioContado - vp) / precioContado * 100.0;

        String senal;
        if (ahorroReal > AHORRO_REAL_CONVIENE_CUOTAS) {
            senal = "conviene_cuotas";
        } else if (ahorroReal < AHORRO_REAL_CONVIENE_CONTADO) {
            senal = "conviene_contado";
        } else {
            senal = "indistinto";
        }

        return new SenalFinanciacion(senal, ahorroReal, vp, cuota, cuotas, recargoPct);
    }
}
