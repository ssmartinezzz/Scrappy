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

    /**
     * Sub-agrupa un pregrupo por similitud Jaccard, greedy: cada producto sin
     * asignar siembra un grupo y absorbe a los que le superen el umbral.
     *
     * <p>La tokenización se hace UNA vez por producto, por adelantado. Antes
     * {@code palabrasSignificativas(j)} vivía dentro del loop interno, así que
     * un pregrupo de k productos se tokenizaba ~k²/2 veces en vez de k — y
     * tokenizar no es barato: {@code String.replaceAll} y {@code String.matches}
     * compilan un {@code Pattern} nuevo por token en cada llamada. Todo esto
     * corre entero en cada request a {@code /api/grupos}, paginado incluido.</p>
     *
     * <p>El orden greedy es intencional y se conserva tal cual: quién siembra
     * cada grupo cambia el resultado, así que recorrer los índices en otro orden
     * no sería una optimización sino otro algoritmo.</p>
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

    /**
     * Jaccard sobre dos bags-of-words: |intersección| / |unión|.
     *
     * <p>Cuenta la intersección recorriendo el set más chico contra el más
     * grande, y deriva la unión por aritmética ({@code |a| + |b| - |inter|}),
     * en vez de materializar dos {@code HashSet} descartables por comparación.
     * Mismo valor exacto — son enteros, no hay redondeo de por medio.</p>
     */
    double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> menor = a.size() <= b.size() ? a : b;
        Set<String> mayor = menor == a ? b : a;
        int interseccion = 0;
        for (String token : menor) if (mayor.contains(token)) interseccion++;
        return (double) interseccion / (a.size() + b.size() - interseccion);
    }

    // Compilados una vez: String.split/replaceAll/matches compilan un Pattern
    // nuevo en cada llamada, y esto corre por token y por producto.
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
