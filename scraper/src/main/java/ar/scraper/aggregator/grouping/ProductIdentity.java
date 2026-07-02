package ar.scraper.aggregator.grouping;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Identity-key computation for {@link GroupingService}'s pre-grouping phase.
 *
 * <p>Extracted from {@code GroupingService.calcularIdentidad} (Work Unit 2 of
 * the aggregator SOLID modularization) — literal move, no behavior change.</p>
 *
 * <p>Identidad de un producto: string que captura marca + modelo sin
 * variables (color, talle, género).</p>
 */
@Component
public class ProductIdentity {

    String calcularIdentidad(Product p) {
        String marca = normalizar(p.marca() != null ? p.marca() : "");
        String nombre = normalizar(p.nombre() != null ? p.nombre() : "");
        String cat   = (p.categoria() != null ? p.categoria() : "").toLowerCase().trim();

        // Combinar marca + nombre, filtrar palabras stop
        String combined = (marca + " " + nombre).trim();
        String[] tokens = combined.split("[\\s\\-_/.,()]+");

        List<String> palabrasSignificativas = Arrays.stream(tokens)
                .map(String::toLowerCase)
                .map(t -> t.replaceAll("[^a-z0-9]", ""))
                .filter(t -> t.length() >= 3)
                .filter(t -> !StopWords.STOP.contains(t))
                // Filtrar números puros que pueden ser talle (1-3 dígitos)
                .filter(t -> !t.matches("^\\d{1,2}$"))
                .limit(5)
                .collect(Collectors.toList());

        String key = String.join("_", palabrasSignificativas);
        return cat.replaceAll("[^a-z]", "") + "_" + key;
    }

    private String normalizar(String s) {
        if (s == null || s.isBlank()) return "";
        return AccentStripper.strip(s.toLowerCase()).trim();
    }
}
