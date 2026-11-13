package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromosDeCorrida;
import ar.scraper.model.Product;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Folds product promos and site promos into the rows {@code sp_upsert_promos} takes. */
public final class FilasDePromo {

    private FilasDePromo() { }

    /** One promo of one site; {@code urls} is empty for a site-wide promo. */
    public record Fila(String sitio, PromoFinanciacion promo, Set<String> urls) { }

    /** {@link PromoFinanciacion#equals} is the natural key, so (sitio, promo) is the row identity. */
    private record Clave(String sitio, PromoFinanciacion promo) { }

    /**
     * Only {@link Alcance#PRODUCTO} promos on a product with a url become links; a promo with any
     * other scope riding on a product is dropped, because linking it would make it product-scoped.
     */
    public static List<Fila> agrupar(List<Product> productos, PromosDeCorrida promos) {
        Map<Clave, Set<String>> filas = new LinkedHashMap<>();
        promos.delSitio().forEach((sitio, lista) -> lista.stream()
                .filter(p -> p.alcance() == Alcance.SITIO)
                .forEach(p -> filas.computeIfAbsent(new Clave(sitio, p), k -> new LinkedHashSet<>())));
        for (Product producto : productos) {
            if (StringUtils.isBlank(producto.url())) continue;
            for (PromoFinanciacion p : producto.promos()) {
                if (p.alcance() != Alcance.PRODUCTO) continue;
                filas.computeIfAbsent(new Clave(producto.sitio(), p), k -> new LinkedHashSet<>())
                        .add(producto.url());
            }
        }
        List<Fila> resultado = new ArrayList<>(filas.size());
        filas.forEach((k, urls) -> resultado.add(new Fila(k.sitio(), k.promo(), urls)));
        return resultado;
    }
}
