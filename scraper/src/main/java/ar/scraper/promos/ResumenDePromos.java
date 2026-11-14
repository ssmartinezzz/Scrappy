package ar.scraper.promos;

import ar.scraper.model.promo.PromoVigente;
import ar.scraper.model.promo.TipoPromo;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Single owner of "which promo is more useful" and "which medios come first". A discount beats
 * interest-free installments (bigger percentage first, then more installments), and a surcharge is
 * the least useful and never the best promo. Ties go to the medios the user cares about, then to the
 * name, so the order is deterministic.
 */
public final class ResumenDePromos {

    /** Shown first, in this order: Mercado Pago, transfer and Santander. */
    private static final List<String> PRIORIDAD = List.of("Mercado Pago", "Transferencia", "Santander");

    private static final Comparator<PromoVigente> POR_UTILIDAD = Comparator
            .comparingInt((PromoVigente p) -> rango(p.tipo()))
            .thenComparing(p -> pctOrdenable(p))
            .thenComparing(p -> -(p.cuotas() == null ? 0 : p.cuotas()))
            .thenComparing(p -> rangoMedio(p.medio()))
            .thenComparing(PromoVigente::medio)
            .thenComparing(p -> p.red() == null ? "" : p.red());

    private ResumenDePromos() {}

    /** Most useful first. */
    public static List<PromoVigente> ordenar(List<PromoVigente> promos) {
        return promos.stream().sorted(POR_UTILIDAD).toList();
    }

    /** The single best promo: the first of {@link #ordenar}, unless that is a surcharge. */
    public static Optional<PromoVigente> mejor(List<PromoVigente> promos) {
        return promos.stream().filter(p -> p.tipo() != TipoPromo.RECARGO).min(POR_UTILIDAD);
    }

    /** Distinct canonical medios of the promos: priority ones first, then alphabetical. */
    public static List<String> medios(List<PromoVigente> promos) {
        return promos.stream().map(PromoVigente::medio).distinct()
                .sorted(Comparator.comparingInt(ResumenDePromos::rangoMedio).thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** Ascending sort key: a discount's bigger pct sorts first, a surcharge's smaller pct sorts first. */
    private static BigDecimal pctOrdenable(PromoVigente p) {
        BigDecimal pct = p.pct() == null ? BigDecimal.ZERO : p.pct();
        return p.tipo() == TipoPromo.RECARGO ? pct : pct.negate();
    }

    private static int rango(TipoPromo tipo) {
        return switch (tipo) {
            case DESCUENTO -> 0;
            case CUOTAS_SIN_INTERES -> 1;
            case RECARGO -> 2;
        };
    }

    private static int rangoMedio(String medio) {
        int i = PRIORIDAD.indexOf(medio);
        return i < 0 ? PRIORIDAD.size() : i;
    }
}
