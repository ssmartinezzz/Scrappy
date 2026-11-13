package ar.scraper.model.promo;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One payment promo. The site is not part of it: the caller carries it. Invariants are the ones of
 * the {@code promo_financiacion} CHECKs, so an invalid promo never reaches the database. Equality is
 * the natural key of {@code uq_promo_financiacion_natural}; vigencia and source text do not count.
 */
public record PromoFinanciacion(String medioPago, TipoMedio tipoMedio, TipoPromo tipo, Integer cuotas,
                                BigDecimal pct, Alcance alcance, LocalDate vigenciaDesde,
                                LocalDate vigenciaHasta, String textoFuente) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public PromoFinanciacion {
        if (StringUtils.isBlank(medioPago)) throw new IllegalArgumentException("medioPago is required");
        Objects.requireNonNull(tipoMedio, "tipoMedio");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(alcance, "alcance");
        if (cuotas != null && cuotas <= 0) throw new IllegalArgumentException("cuotas must be > 0: " + cuotas);
        if (pct != null) pct = pct.setScale(2, RoundingMode.HALF_UP);
        if (tipo == TipoPromo.CUOTAS_SIN_INTERES) {
            if (cuotas == null) throw new IllegalArgumentException("cuotas_sin_interes requires cuotas");
        } else if (pct == null) {
            throw new IllegalArgumentException(tipo + " requires pct");
        }
        if (pct != null && (pct.signum() <= 0 || pct.compareTo(CIEN) > 0)) {
            throw new IllegalArgumentException("pct must be in (0, 100]: " + pct);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PromoFinanciacion p
                && medioPago.equals(p.medioPago) && tipo == p.tipo && alcance == p.alcance
                && Objects.equals(cuotas, p.cuotas) && Objects.equals(pct, p.pct);
    }

    @Override
    public int hashCode() {
        return Objects.hash(medioPago, tipo, alcance, cuotas, pct);
    }
}
