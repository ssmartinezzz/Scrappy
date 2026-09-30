package ar.scraper.aggregator.grouping;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Identidad de un producto: string que captura marca + modelo sin variables (color, talle, género).
 */
@Component
public class ProductIdentity {

    private static final Pattern SEPARADORES = Pattern.compile("[\\s\\-_/.,()]+");
    private static final Pattern NO_ALFANUM   = Pattern.compile("[^a-z0-9]");
    private static final Pattern NO_LETRA     = Pattern.compile("[^a-z]");
    private static final Pattern NUMERO_CORTO = Pattern.compile("^\\d{1,2}$");

    String calcularIdentidad(Product p) {
        String marca = normalizar(p.marca() != null ? p.marca() : "");
        String nombre = normalizar(p.nombre() != null ? p.nombre() : "");
        String cat   = (p.categoria() != null ? p.categoria() : "").toLowerCase().trim();

        String combined = (marca + " " + nombre).trim();
        String[] tokens = SEPARADORES.split(combined);

        List<String> palabrasSignificativas = Arrays.stream(tokens)
                .map(String::toLowerCase)
                .map(t -> NO_ALFANUM.matcher(t).replaceAll(""))
                .filter(t -> t.length() >= 3)
                .filter(t -> !StopWords.STOP.contains(t))
                .filter(t -> !NUMERO_CORTO.matcher(t).matches())
                .limit(5)
                .collect(Collectors.toList());

        String key = String.join("_", palabrasSignificativas);
        return NO_LETRA.matcher(cat).replaceAll("") + "_" + key;
    }

    private String normalizar(String s) {
        if (StringUtils.isBlank(s)) return "";
        return AccentStripper.strip(s.toLowerCase()).trim();
    }
}
