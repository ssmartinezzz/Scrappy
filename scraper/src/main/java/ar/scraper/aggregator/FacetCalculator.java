package ar.scraper.aggregator;

import ar.scraper.model.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure, stateless computation of catalog facets (talles, generos, categorias,
 * marcas, badges, subCategorias) from a product list.
 *
 * <p>Extracted verbatim from {@code ResultAggregator.calcularFacets}/
 * {@code sortTalles} (Work Unit 9 of the aggregator SOLID modularization) —
 * pure relocation, no behavior change. {@link ResultAggregator#calcularFacets}
 * keeps its public signature as a thin delegate to this class (see the
 * migration tracker for the rationale: it preserves the ~10 external test
 * call sites in {@code ar.scraper.web} that build fixtures against it).</p>
 */
public final class FacetCalculator {

    private FacetCalculator() {}

    public static ResultAggregator.Facets calcular(List<Product> productos) {
        Map<String, Long> talles = new LinkedHashMap<>();
        for (Product p : productos) {
            if (p.talles() != null)
                for (String t : p.talles())
                    if (!t.isBlank()) talles.merge(t, 1L, Long::sum);
        }
        talles = sortTalles(talles);

        Map<String, Long> generos = new LinkedHashMap<>();
        for (Product p : productos) {
            String g = p.genero() != null ? p.genero().trim().toLowerCase() : "";
            if (!g.isBlank()) generos.merge(g, 1L, Long::sum);
        }

        Map<String, Long> categorias = new LinkedHashMap<>();
        for (Product p : productos) {
            String cat = p.categoria() != null ? p.categoria().trim() : "";
            if (!cat.isBlank()) {
                String key = Character.toUpperCase(cat.charAt(0)) + cat.substring(1).toLowerCase();
                categorias.merge(key, 1L, Long::sum);
            }
        }
        categorias = categorias.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> marcas = new LinkedHashMap<>();
        for (Product p : productos) {
            String m = p.marca() != null ? p.marca().trim() : "";
            if (!m.isBlank()) marcas.merge(m, 1L, Long::sum);
        }
        marcas = marcas.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> badges = new LinkedHashMap<>();
        for (Product p : productos) {
            String b = (p.ml() != null && p.ml().badge() != null) ? p.ml().badge().trim() : "";
            if (!b.isBlank()) badges.merge(b, 1L, Long::sum);
        }

        Map<String, Long> subCategorias = new LinkedHashMap<>();
        for (Product p : productos) {
            String sc = p.subCategoria() != null ? p.subCategoria().trim() : "";
            if (!sc.isBlank()) subCategorias.merge(sc, 1L, Long::sum);
        }
        subCategorias = subCategorias.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        // T6.5/T6.6 (fashion-image-classification PR6): image-derived visual
        // attribute facets — additive, mirror the badges/subCategorias pattern
        // (blank values excluded from the count).
        Map<String, Long> fits             = contarNoBlanco(productos, p -> p.visual() != null ? p.visual().fit() : "");
        Map<String, Long> estampados       = contarNoBlanco(productos, p -> p.visual() != null ? p.visual().estampado() : "");
        Map<String, Long> escotes          = contarNoBlanco(productos, p -> p.visual() != null ? p.visual().escote() : "");
        Map<String, Long> colorDominantes  = contarNoBlanco(productos, p -> p.visual() != null ? p.visual().colorDominante() : "");

        return new ResultAggregator.Facets(talles, generos, categorias, marcas, badges, subCategorias,
                fits, estampados, escotes, colorDominantes);
    }

    /** Counts non-blank values extracted by {@code valor} from each product, preserving first-seen order. */
    private static Map<String, Long> contarNoBlanco(List<Product> productos,
            java.util.function.Function<Product, String> valor) {
        Map<String, Long> conteo = new LinkedHashMap<>();
        for (Product p : productos) {
            String v = valor.apply(p);
            if (v != null && !v.isBlank()) conteo.merge(v, 1L, Long::sum);
        }
        return conteo;
    }

    private static Map<String, Long> sortTalles(Map<String, Long> talles) {
        List<String> orden = List.of("XS","S","M","L","XL","XXL","XXXL","3XL","4XL");
        List<String> conocidos = new ArrayList<>(), numericos = new ArrayList<>(), resto = new ArrayList<>();
        for (String t : talles.keySet()) {
            String u = t.toUpperCase();
            if (orden.contains(u)) conocidos.add(t);
            else if (t.matches("\\d+(\\.\\d+)?")) numericos.add(t);
            else resto.add(t);
        }
        conocidos.sort(Comparator.comparingInt(t -> { int i = orden.indexOf(t.toUpperCase()); return i >= 0 ? i : 999; }));
        numericos.sort(Comparator.comparingDouble(Double::parseDouble));
        resto.sort(String.CASE_INSENSITIVE_ORDER);
        Map<String, Long> result = new LinkedHashMap<>();
        for (String t : conocidos) result.put(t, talles.get(t));
        for (String t : numericos) result.put(t, talles.get(t));
        for (String t : resto)     result.put(t, talles.get(t));
        return result;
    }
}
