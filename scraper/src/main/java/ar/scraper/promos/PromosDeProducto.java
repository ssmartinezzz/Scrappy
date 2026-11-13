package ar.scraper.promos;

import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.TipoPromo;

import java.math.BigDecimal;
import java.util.Optional;

/** Builds the product-scoped promos the extractors agree on, so none of them spells a medio itself. */
final class PromosDeProducto {

    private PromosDeProducto() {}

    /** Empty when {@code medioCrudo} is not a known medio: a bank is never guessed (CODE-5). */
    static Optional<PromoFinanciacion> cuotasSinInteres(String medioCrudo, int cuotas) {
        return MediosDePago.resolver(medioCrudo).map(m -> cuotasSinInteres(m, cuotas));
    }

    /** Installments the site states or proves without naming who offers them: the generic card. */
    static PromoFinanciacion cuotasSinInteresGenericas(int cuotas) {
        return cuotasSinInteres(MediosDePago.resolver("Tarjeta de crédito").orElseThrow(), cuotas);
    }

    static PromoFinanciacion descuento(String medioCrudo, BigDecimal pct) {
        MedioPago m = MediosDePago.resolver(medioCrudo).orElseThrow();
        return new PromoFinanciacion(m.nombre(), m.tipo(), TipoPromo.DESCUENTO, null, pct, Alcance.PRODUCTO,
                null, null, null);
    }

    private static PromoFinanciacion cuotasSinInteres(MedioPago m, int cuotas) {
        return new PromoFinanciacion(m.nombre(), m.tipo(), TipoPromo.CUOTAS_SIN_INTERES, cuotas, null,
                Alcance.PRODUCTO, null, null, null);
    }
}
