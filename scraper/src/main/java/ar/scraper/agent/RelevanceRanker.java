package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranking BM25F sobre los productos de un snapshot. Campos: nombre (1.0), marca (2.0),
 * categoria + subCategoria (1.5); k1 = 1.2, b = 0.75 con normalización de largo por campo.
 *
 * <p>Cada término de la consulta se compara contra el VOCABULARIO del índice (no contra cada
 * producto), con un nivel de coincidencia que multiplica su aporte: palabra exacta 1.0; prefijo
 * (término de 4+ letras) 0.8; sufijo de unidad tras dígitos ("tb" ~ "1tb") 0.8; typo
 * (Damerau-Levenshtein ≤ 1 desde 5 letras, ≤ 2 desde 8, sólo entre palabras alfabéticas) 0.5.
 * Nunca substring crudo: el espacio es el límite de palabra ("ram" no está en "programa").</p>
 *
 * <p>IDF estándar con +1 dentro del log (siempre positivo). El df es el de la forma exacta del
 * término si existe en el vocabulario; si no (prefijo/typo), la cantidad de documentos que
 * matchearon de cualquier modo.</p>
 *
 * <p>El índice se arma una vez por instancia de snapshot y se cachea por identidad.</p>
 */
final class RelevanceRanker {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final double[] PESO_CAMPO = {1.0, 2.0, 1.5};
    private static final int NOMBRE = 0, MARCA = 1, CATEGORIA = 2, CAMPOS = 3;

    private static final double NIVEL_EXACTO = 1.0;
    private static final double NIVEL_PREFIJO = 0.8;
    private static final double NIVEL_UNIDAD = 0.8;
    private static final double NIVEL_TYPO = 0.5;
    private static final int MIN_PREFIJO = 4;

    /** score y máscara de términos matcheados, alineados con el orden de {@code snapshot.productos()}. */
    record Scores(double[] score, int[] mask, int tokenCount) {}

    private record Posting(int doc, int campo, int tf) {}

    static final class Index {
        final int n;
        final int[][] largo = new int[CAMPOS][];
        final double[] largoMedio = new double[CAMPOS];
        final Map<String, List<Posting>> postings = new HashMap<>();
        final Map<String, Integer> df = new HashMap<>();

        Index(List<Product> productos) {
            n = productos.size();
            for (int f = 0; f < CAMPOS; f++) largo[f] = new int[n];
            long[] suma = new long[CAMPOS];
            Map<String, Integer> tf = new HashMap<>();
            for (int d = 0; d < n; d++) {
                Product p = productos.get(d);
                indexar(d, NOMBRE, p.nombre(), tf, suma);
                indexar(d, MARCA, p.marca(), tf, suma);
                indexar(d, CATEGORIA, nz(p.categoria()) + " " + nz(p.subCategoria()), tf, suma);
            }
            for (int f = 0; f < CAMPOS; f++) largoMedio[f] = n == 0 || suma[f] == 0 ? 1.0 : (double) suma[f] / n;
            for (var e : postings.entrySet()) {
                int docs = 0, ultimo = -1;
                for (Posting p : e.getValue()) {
                    if (p.doc != ultimo) { docs++; ultimo = p.doc; }
                }
                df.put(e.getKey(), docs);
            }
        }

        private void indexar(int doc, int campo, String texto, Map<String, Integer> tf, long[] suma) {
            List<String> terms = QueryTokenizer.terms(texto);
            largo[campo][doc] = terms.size();
            suma[campo] += terms.size();
            tf.clear();
            for (String t : terms) tf.merge(t, 1, Integer::sum);
            for (var e : tf.entrySet()) {
                postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new Posting(doc, campo, e.getValue()));
            }
        }

        private double tfNormalizado(Posting p) {
            double norma = 1 - B + B * largo[p.campo][p.doc] / largoMedio[p.campo];
            return PESO_CAMPO[p.campo] * p.tf / norma;
        }
    }

    private record Cached(AggregatedResult fuente, Index indice) {}

    private volatile Cached cache;

    Index indexFor(AggregatedResult snapshot) {
        Cached c = cache;
        if (c != null && c.fuente == snapshot) return c.indice;
        synchronized (this) {
            c = cache;
            if (c != null && c.fuente == snapshot) return c.indice;
            Index idx = new Index(snapshot.productos());
            cache = new Cached(snapshot, idx);
            return idx;
        }
    }

    Scores score(AggregatedResult snapshot, List<String> stems) {
        Index ix = indexFor(snapshot);
        double[] score = new double[ix.n];
        int[] mask = new int[ix.n];
        for (int t = 0; t < stems.size(); t++) {
            String token = stems.get(t);
            double[] tf = new double[ix.n];
            double[] nivel = new double[ix.n];
            for (var e : ix.postings.entrySet()) {
                double lv = nivelCoincidencia(token, e.getKey());
                if (lv == 0) continue;
                for (Posting p : e.getValue()) {
                    tf[p.doc] += ix.tfNormalizado(p);
                    if (lv > nivel[p.doc]) nivel[p.doc] = lv;
                }
            }
            int matcheados = 0;
            for (int d = 0; d < ix.n; d++) if (nivel[d] > 0) matcheados++;
            if (matcheados == 0) continue;
            Integer exacto = ix.df.get(token);
            int df = exacto != null ? exacto : matcheados;
            double idf = Math.log(1 + (ix.n - df + 0.5) / (df + 0.5));
            for (int d = 0; d < ix.n; d++) {
                if (nivel[d] == 0) continue;
                mask[d] |= 1 << t;
                score[d] += nivel[d] * idf * tf[d] / (K1 + tf[d]);
            }
        }
        return new Scores(score, mask, stems.size());
    }

    static double nivelCoincidencia(String token, String palabra) {
        if (palabra.equals(token)) return NIVEL_EXACTO;
        int lt = token.length(), lp = palabra.length();
        if (lt >= MIN_PREFIJO && palabra.startsWith(token)) return NIVEL_PREFIJO;
        boolean alfaToken = soloLetras(token);
        if (alfaToken && lp > lt && palabra.endsWith(token) && soloDigitos(palabra, lp - lt)) return NIVEL_UNIDAD;
        int max = lt >= 8 ? 2 : lt >= 5 ? 1 : 0;
        if (max > 0 && alfaToken && Math.abs(lp - lt) <= max && soloLetras(palabra)
                && distancia(token, palabra, max) <= max) {
            return NIVEL_TYPO;
        }
        return 0;
    }

    private static boolean soloLetras(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    /** ¿Los primeros {@code hasta} caracteres son todos dígitos (y hay al menos uno)? */
    private static boolean soloDigitos(String s, int hasta) {
        if (hasta == 0) return false;
        for (int i = 0; i < hasta; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Optimal string alignment (Damerau-Levenshtein con transposición adyacente). */
    static int distancia(String a, String b, int tope) {
        int la = a.length(), lb = b.length();
        int[] prev2 = new int[lb + 1], prev = new int[lb + 1], cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int minFila = cur[0];
            for (int j = 1; j <= lb; j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + costo);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
                if (v < minFila) minFila = v;
            }
            if (minFila > tope) return tope + 1;
            int[] tmp = prev2; prev2 = prev; prev = cur; cur = tmp;
        }
        return prev[lb];
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
