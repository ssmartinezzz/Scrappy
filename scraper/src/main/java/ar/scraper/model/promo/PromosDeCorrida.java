package ar.scraper.model.promo;


import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a run hands the upsert besides products: the site-level promos per site and the sites whose
 * promo read succeeded, which is the only scope {@code sp_upsert_promos} may deactivate in.
 */
public record PromosDeCorrida(Map<String, List<PromoFinanciacion>> delSitio, Set<String> sitiosLeidos) {

    public static final PromosDeCorrida VACIO = new PromosDeCorrida(Map.of(), Set.of());

    public PromosDeCorrida {
        delSitio = Map.copyOf(delSitio);
        sitiosLeidos = Set.copyOf(sitiosLeidos);
    }

    public boolean esVacio() {
        return delSitio.isEmpty() && sitiosLeidos.isEmpty();
    }
}
