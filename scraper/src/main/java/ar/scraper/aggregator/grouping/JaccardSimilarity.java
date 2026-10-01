package ar.scraper.aggregator.grouping;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Delegates the shared accent-stripping step to {@link AccentStripper} (ADR-4); the stop-word
 * filtering here stays local to this class.
 */
@Component
public class JaccardSimilarity {

    private static final double JACCARD_THRESHOLD = 0.55;

    /**
     * Sub-agrupa un pregrupo por similitud Jaccard, greedy: cada producto sin asignar siembra un
     * grupo y absorbe a los que le superen el umbral.
     */
    List<List<Product>> subAgruparPorJaccard(List<Product> productos) {
        List<List<Product>> grupos = new ArrayList<>();
        boolean[] asignado = new boolean[productos.size()];

        List<Set<String>> palabras = new ArrayList<>(productos.size());
        for (Product p : productos) palabras.add(palabrasSignificativas(p));

        for (int i = 0; i < productos.size(); i++) {
            if (asignado[i]) continue;
            List<Product> grupo = new ArrayList<>();
            grupo.add(productos.get(i));
            asignado[i] = true;
            Set<String> wordsI = palabras.get(i);

            for (int j = i + 1; j < productos.size(); j++) {
                if (asignado[j]) continue;
                // Solo agrupar si son de sitios distintos
                if (productos.get(i).sitio().equals(productos.get(j).sitio())) continue;
                if (jaccardSimilarity(wordsI, palabras.get(j)) >= JACCARD_THRESHOLD) {
                    grupo.add(productos.get(j));
                    asignado[j] = true;
                }
            }
            grupos.add(grupo);
        }
        return grupos;
    }

    /** Mismo valor exacto — son enteros, no hay redondeo de por medio. */
    double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> menor = a.size() <= b.size() ? a : b;
        Set<String> mayor = menor == a ? b : a;
        int interseccion = 0;
        for (String token : menor) if (mayor.contains(token)) interseccion++;
        return (double) interseccion / (a.size() + b.size() - interseccion);
    }

    private static final Pattern SEPARADORES  = Pattern.compile("[\\s\\-_/.,()]+");
    private static final Pattern NO_ALFANUM   = Pattern.compile("[^a-z0-9]");
    private static final Pattern NUMERO_CORTO = Pattern.compile("^\\d{1,2}$");

    Set<String> palabrasSignificativas(Product p) {
        String texto = ((p.marca() != null ? p.marca() : "") + " "
                     + (p.nombre() != null ? p.nombre() : "")).toLowerCase();
        texto = AccentStripper.strip(texto);
        return Arrays.stream(SEPARADORES.split(texto))
                .map(t -> NO_ALFANUM.matcher(t).replaceAll(""))
                .filter(t -> t.length() >= 3 && !StopWords.STOP.contains(t))
                .filter(t -> !NUMERO_CORTO.matcher(t).matches())
                .collect(Collectors.toSet());
    }
}
