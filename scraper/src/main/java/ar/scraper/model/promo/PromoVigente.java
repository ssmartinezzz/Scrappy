package ar.scraper.model.promo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A stored promo as it is read back: the same facts as {@link PromoFinanciacion} plus the site it
 * belongs to and when a run last saw it. {@code sitio} is the site's display name (the key of
 * {@code Product.sitio}); {@code red} is the required card network or null.
 */
public record PromoVigente(String sitio, String medio, TipoMedio tipoMedio, String red, TipoPromo tipo,
                           Integer cuotas, BigDecimal pct, Alcance alcance, LocalDate vigenciaDesde,
                           LocalDate vigenciaHasta, String textoFuente, Instant vistoAt) {
}
