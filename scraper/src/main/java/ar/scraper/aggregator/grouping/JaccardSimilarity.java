package ar.scraper.aggregator.grouping;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fine sub-grouping by Jaccard similarity over bag-of-words, within an
 * identity pre-group.
 *
 * <p>Extracted from {@code GroupingService.subAgruparPorJaccard} /
 * {@code jaccardSimilarity} / {@code palabrasSignificativas} (Work Unit 2 of
 * the aggregator SOLID modularization) — literal move, no behavior change.
 * Delegates the shared accent-stripping step to {@link AccentStripper}
 * (ADR-4); the stop-word filtering here stays local to this class.</p>
 *
 * <p>Evita el falso positivo de "Nike Air Force" vs "Nike Air Max":
 *   words("Nike Air Force") = {nike, air, force}
 *   words("Nike Air Max")   = {nike, air, max}
 *   Jaccard = |{nike,air}| / |{nike,air,force,max}| = 2/4 = 0.5 → umbral no superado ✗
 *
 *   words("Nike Air Force 1 Blanco") = {nike, air, force}
 *   words("Nike Air Force 1 Negro")  = {nike, air, force}
 *   Jaccard = 3/3 = 1.0 ✓</p>
 */
@Component
public class JaccardSimilarity {

    // Umbral mínimo de similitud Jaccard para considerar 2 productos como "el mismo artículo"
    private static final double JACCARD_THRESHOLD = 0.55;

    List<List<Product>> subAgruparPorJaccard(List<Product> productos) {
        List<List<Product>> grupos = new ArrayList<>();
        boolean[] asignado = new boolean[productos.size()];

        for (int i = 0; i < productos.size(); i++) {
            if (asignado[i]) continue;
            List<Product> grupo = new ArrayList<>();
            grupo.add(productos.get(i));
            asignado[i] = true;
            Set<String> wordsI = palabrasSignificativas(productos.get(i));

            for (int j = i + 1; j < productos.size(); j++) {
                if (asignado[j]) continue;
                // Solo agrupar si son de sitios distintos
                if (productos.get(i).sitio().equals(productos.get(j).sitio())) continue;
                Set<String> wordsJ = palabrasSignificativas(productos.get(j));
                if (jaccardSimilarity(wordsI, wordsJ) >= JACCARD_THRESHOLD) {
                    grupo.add(productos.get(j));
                    asignado[j] = true;
                }
            }
            grupos.add(grupo);
        }
        return grupos;
    }

    double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> union        = new HashSet<>(a); union.addAll(b);
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    Set<String> palabrasSignificativas(Product p) {
        String texto = ((p.marca() != null ? p.marca() : "") + " "
                     + (p.nombre() != null ? p.nombre() : "")).toLowerCase();
        texto = AccentStripper.strip(texto);
        return Arrays.stream(texto.split("[\\s\\-_/.,()]+"))
                .map(t -> t.replaceAll("[^a-z0-9]",""))
                .filter(t -> t.length() >= 3 && !StopWords.STOP.contains(t))
                .filter(t -> !t.matches("^\\d{1,2}$"))
                .collect(Collectors.toSet());
    }
}
