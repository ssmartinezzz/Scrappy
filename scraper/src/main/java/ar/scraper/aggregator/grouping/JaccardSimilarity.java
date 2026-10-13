package ar.scraper.aggregator.grouping;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Delegates the shared accent-stripping step to {@link AccentStripper} (ADR-4); the stop-word
 * filtering here stays local to this class.
 */
@Component
public class JaccardSimilarity {

    private static final double JACCARD_THRESHOLD = 0.60;

    private static final Set<String> KIDS = Set.of("ninos", "ninas", "nino", "nina", "kids", "junior", "infantil");
    private static final Pattern CODIGO_CON_SUFIJO = Pattern.compile("([a-z0-9]*\\d[a-z0-9]*)-([a-z0-9]{1,3})\\b");
    private static final Pattern SEPARADORES_CODIGO = Pattern.compile("[\\s/.,()_]+");
    private static final Pattern TALLE = Pattern.compile("talle\\s*\\d+");
    private static final Pattern VERSION = Pattern.compile("(?<![a-z0-9])(\\d{1,2})(?![a-z0-9])");

    /** Per-product features. The regex-heavy guards are lazy: only pairs above the threshold need them. */
    private final class Rasgos {
        final Set<String> firma;
        final String marca;
        final String nombre;
        final boolean kids;
        private Set<String> codigos;
        private Set<String> versiones;

        Rasgos(Product p, Set<String> firma) {
            this.firma = firma;
            this.marca = AccentStripper.strip(StringUtils.defaultString(p.marca()).toLowerCase()).trim();
            this.nombre = AccentStripper.strip(StringUtils.defaultString(p.nombre()).toLowerCase());
            this.kids = firma.stream().anyMatch(KIDS::contains);
        }

        Set<String> codigos() {
            if (codigos == null) codigos = codigosModelo(nombre);
            return codigos;
        }

        Set<String> versiones() {
            if (versiones == null) versiones = numerosDeVersion(nombre);
            return versiones;
        }
    }

    /**
     * Greedy clustering of a block: each unassigned product seeds a group and absorbs, for every
     * other site, only its best-scoring candidates (ties are colour variants and all stay).
     */
    List<List<Product>> subAgruparPorJaccard(List<Product> productos) {
        int n = productos.size();
        List<Rasgos> rasgos = new ArrayList<>(n);
        Map<String, Integer> frecuencia = new HashMap<>();
        Map<String, Set<String>> palabrasCategoria = new HashMap<>();
        for (Product p : productos) {
            Set<String> categoria = palabrasCategoria.computeIfAbsent(
                    StringUtils.defaultString(p.categoria()), this::tokens);
            Rasgos r = new Rasgos(p, firma(p, categoria));
            rasgos.add(r);
            for (String token : r.firma) frecuencia.merge(token, 1, Integer::sum);
        }

        // Prefix filtering: with tokens ordered rarest first, two sets with Jaccard >= t always
        // share a token within both prefixes, so only prefixes need indexing. Exact, not a heuristic.
        Comparator<String> rarezas = Comparator.<String>comparingInt(frecuencia::get).thenComparing(Comparator.naturalOrder());
        List<List<String>> prefijos = new ArrayList<>(n);
        Map<String, List<Integer>> indice = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<String> orden = new ArrayList<>(rasgos.get(i).firma);
            orden.sort(rarezas);
            List<String> prefijo = orden.subList(0, largoPrefijo(orden.size()));
            prefijos.add(prefijo);
            for (String token : prefijo) indice.computeIfAbsent(token, k -> new ArrayList<>()).add(i);
        }

        List<List<Product>> grupos = new ArrayList<>();
        boolean[] asignado = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (asignado[i]) continue;
            asignado[i] = true;
            List<Product> grupo = new ArrayList<>();
            grupo.add(productos.get(i));
            Rasgos ri = rasgos.get(i);

            TreeSet<Integer> candidatos = new TreeSet<>();
            for (String token : prefijos.get(i)) {
                for (int j : indice.get(token)) if (j > i && !asignado[j]) candidatos.add(j);
            }

            Map<String, Double> mejorPuntaje = new HashMap<>();
            Map<String, List<Integer>> mejores = new LinkedHashMap<>();
            for (int j : candidatos) {
                String sitio = productos.get(j).sitio();
                if (sitio.equals(productos.get(i).sitio())) continue;
                Rasgos rj = rasgos.get(j);
                double puntaje = jaccardSimilarity(ri.firma, rj.firma);
                if (puntaje < JACCARD_THRESHOLD || !compatibles(ri, rj)) continue;
                Double previo = mejorPuntaje.get(sitio);
                if (previo == null || puntaje > previo) {
                    mejorPuntaje.put(sitio, puntaje);
                    mejores.put(sitio, new ArrayList<>(List.of(j)));
                } else if (puntaje == previo) {
                    mejores.get(sitio).add(j);
                }
            }
            for (List<Integer> js : mejores.values()) {
                for (int j : js) {
                    grupo.add(productos.get(j));
                    asignado[j] = true;
                }
            }
            grupos.add(grupo);
        }
        return grupos;
    }

    private static int largoPrefijo(int tamanio) {
        if (tamanio == 0) return 0;
        // The epsilon keeps 0.6 * 5 from rounding up past 3 and shortening the prefix.
        return tamanio - (int) Math.ceil(JACCARD_THRESHOLD * tamanio - 1e-9) + 1;
    }

    private boolean compatibles(Rasgos a, Rasgos b) {
        if (a.kids != b.kids) return false;
        if (!a.marca.isEmpty() && !b.marca.isEmpty() && !a.marca.equals(b.marca)) return false;
        if (!a.codigos().isEmpty() && !b.codigos().isEmpty() && !a.codigos().equals(b.codigos())) return false;
        return a.versiones().isEmpty() || b.versiones().isEmpty() || a.versiones().equals(b.versiones());
    }

    private Set<String> codigosModelo(String nombre) {
        String unido = CODIGO_CON_SUFIJO.matcher(nombre).replaceAll("$1$2");
        return Arrays.stream(SEPARADORES_CODIGO.split(unido))
                .filter(t -> t.length() >= 3)
                .filter(t -> t.chars().anyMatch(Character::isDigit) && t.chars().anyMatch(Character::isLetter))
                .collect(Collectors.toSet());
    }

    private Set<String> numerosDeVersion(String nombre) {
        Matcher m = VERSION.matcher(TALLE.matcher(nombre).replaceAll(" "));
        Set<String> out = new HashSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
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

    /** Significant words minus the product's own category words (equal, or equal modulo a trailing "s"). */
    Set<String> firma(Product p) {
        return firma(p, tokens(StringUtils.defaultString(p.categoria())));
    }

    private Set<String> firma(Product p, Set<String> categoria) {
        return palabrasSignificativas(p).stream()
                .filter(t -> categoria.stream().noneMatch(c -> mismaPalabra(t, c)))
                .collect(Collectors.toSet());
    }

    private static boolean mismaPalabra(String a, String b) {
        return StringUtils.removeEnd(a, "s").equals(StringUtils.removeEnd(b, "s"));
    }

    private Set<String> tokens(String texto) {
        return Arrays.stream(SEPARADORES.split(AccentStripper.strip(texto.toLowerCase())))
                .map(t -> NO_ALFANUM.matcher(t).replaceAll(""))
                .filter(t -> t.length() >= 3)
                .collect(Collectors.toSet());
    }

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
