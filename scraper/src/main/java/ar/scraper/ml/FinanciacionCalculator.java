package ar.scraper.ml;

import ar.scraper.model.Product.SenalFinanciacion;

/**
 * Pure, dependency-free financing-signal classifier ("¿conviene en
 * cuotas?"). Mirrors {@link SenalCalculator}'s static-pure style: no
 * Spring/DB dependencies, named threshold constants, trivially
 * unit-testable. Fully independent from {@code SenalCalculator}/{@code
 * SenalCompra} — never merged into the same score or badge.
 *
 * <p>Computes the present value (VP) of {@code n} equal installments
 * discounted by a monthly inflation rate, then compares it against the
 * cash price to classify whether paying in installments or in cash is more
 * favorable in real terms.</p>
 */
public final class FinanciacionCalculator {

    /** ahorroReal &gt; this value -&gt; "conviene_cuotas". Shares the existing
     *  {@code SenalCalculator.TENDENCIA_UMBRAL} (5.0) significance threshold. */
    private static final double AHORRO_REAL_CONVIENE_CUOTAS = 5.0;

    /** ahorroReal &lt; this value -&gt; "conviene_contado". */
    private static final double AHORRO_REAL_CONVIENE_CONTADO = -5.0;

    private FinanciacionCalculator() {
    }

    /**
     * Computes the financing signal for a single product against the
     * active preset's surcharge/installment count and the current monthly
     * inflation rate.
     *
     * @param precioContado cash price; must be &gt; 0 or {@link SenalFinanciacion#EMPTY} is returned
     * @param recargoPct    installment surcharge percent (e.g. 40 for +40%); may be negative (a discount)
     * @param cuotas        number of installments ({@code n}); must be &gt; 0 or {@link SenalFinanciacion#EMPTY} is returned
     * @param iMensual      monthly inflation rate already divided by 100 (e.g. 0.035 for 3.5%); may be &lt;= 0
     * @return the resolved {@link SenalFinanciacion}; {@link SenalFinanciacion#EMPTY} ("sin_datos")
     *         when there is no active preset, {@code cuotas <= 0}, or {@code precioContado <= 0}
     */
    public static SenalFinanciacion compute(double precioContado, double recargoPct, int cuotas, double iMensual) {
        if (precioContado <= 0 || cuotas <= 0) {
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
