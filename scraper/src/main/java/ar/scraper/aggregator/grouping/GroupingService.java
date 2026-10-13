package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

/**
 * Criterio de identidad: se bloquea por familia de categoría y, dentro de cada bloque, se agrupan
 * productos de sitios distintos con la misma marca, modelo y versión (firma de palabras sin color,
 * talle, género ni relleno), quedándose con la mejor coincidencia por sitio.
 */
@Component
@RequiredArgsConstructor
public class GroupingService {

    private final ProductIdentity productIdentity;
    private final JaccardSimilarity jaccardSimilarity;

    public List<ProductGroup> agrupar(List<Product> productos, boolean soloMultiSitio) {
        Map<String, List<Product>> preGrupos = new LinkedHashMap<>();
        for (Product p : productos) {
            String key = productIdentity.calcularIdentidad(p);
            preGrupos.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        // Paso 2: dentro de cada bloque, agrupar por similitud Jaccard de la firma, con guardas de
        // marca, código de modelo, versión y niños
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
