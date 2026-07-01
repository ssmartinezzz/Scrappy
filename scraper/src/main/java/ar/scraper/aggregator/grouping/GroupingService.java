package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agrupa productos del mismo artículo provenientes de distintos sitios.
 *
 * Criterio de identidad: categoría + marca + modelo normalizado
 * (sin color, talle, género ni descriptores variables).
 *
 * Ejemplo:
 *   "Nike Air Force 1 Blanco Talle 42" (VCP)  ─┐
 *   "Nike Air Force 1 Negro"            (Freres)├─ mismo grupo
 *   "Nike Air Force 1"                 (Sporting)┘
 *
 * <p>Thin orchestrator (Work Unit 2 of the aggregator SOLID modularization):
 * two-phase grouping — pre-group by {@link ProductIdentity}, sub-group by
 * {@link JaccardSimilarity}, map to {@link ProductGroup}, filter
 * {@code soloMultiSitio}, sort by minimum price. Public
 * {@code agrupar(List, boolean)} signature is unchanged from the
 * pre-extraction class — {@code ApiController} call site unaffected.</p>
 */
@Component
public class GroupingService {

    private final ProductIdentity productIdentity;
    private final JaccardSimilarity jaccardSimilarity;

    public GroupingService(ProductIdentity productIdentity, JaccardSimilarity jaccardSimilarity) {
        this.productIdentity = productIdentity;
        this.jaccardSimilarity = jaccardSimilarity;
    }

    /**
     * Agrupa una lista de productos en grupos de artículo comparable.
     *
     * @param productos lista ya normalizada y enriquecida con ML
     * @param soloMultiSitio si true, solo retorna grupos con 2+ sitios distintos
     */
    public List<ProductGroup> agrupar(List<Product> productos, boolean soloMultiSitio) {
        // Paso 1: agrupar por clave de identidad aproximada (prefijo eficiente)
        Map<String, List<Product>> preGrupos = new LinkedHashMap<>();
        for (Product p : productos) {
            String key = productIdentity.calcularIdentidad(p);
            preGrupos.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        // Paso 2: dentro de cada pregrupo, verificar similitud Jaccard real
        // Evita que productos con mismo prefijo pero diferente modelo se agrupen
        List<List<Product>> gruposFinales = new ArrayList<>();
        for (List<Product> preGrupo : preGrupos.values()) {
            if (preGrupo.size() == 1) {
                gruposFinales.add(preGrupo);
                continue;
            }
            // Sub-agrupar por Jaccard dentro del pregrupo
            gruposFinales.addAll(jaccardSimilarity.subAgruparPorJaccard(preGrupo));
        }

        return gruposFinales.stream()
                .filter(g -> !g.isEmpty())
                .map(ProductGroup::new)
                .filter(g -> !soloMultiSitio || g.sitiosDistintos() >= 2)
                .sorted(Comparator.comparingDouble(ProductGroup::precioMinimo))
                .collect(Collectors.toList());
    }
}
