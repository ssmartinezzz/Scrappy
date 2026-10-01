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
 * Criterio de identidad: categoría + marca + modelo normalizado (sin color, talle, género ni
 * descriptores variables).
 */
@Component
public class GroupingService {

    private final ProductIdentity productIdentity;
    private final JaccardSimilarity jaccardSimilarity;

    public GroupingService(ProductIdentity productIdentity, JaccardSimilarity jaccardSimilarity) {
        this.productIdentity = productIdentity;
        this.jaccardSimilarity = jaccardSimilarity;
    }

    public List<ProductGroup> agrupar(List<Product> productos, boolean soloMultiSitio) {
        Map<String, List<Product>> preGrupos = new LinkedHashMap<>();
        for (Product p : productos) {
            String key = productIdentity.calcularIdentidad(p);
            preGrupos.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        // Paso 2: dentro de cada pregrupo, verificar similitud Jaccard real Evita que productos con
        // mismo prefijo pero diferente modelo se agrupen
        List<List<Product>> gruposFinales = new ArrayList<>();
        for (List<Product> preGrupo : preGrupos.values()) {
            if (preGrupo.size() == 1) {
                gruposFinales.add(preGrupo);
                continue;
            }
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
