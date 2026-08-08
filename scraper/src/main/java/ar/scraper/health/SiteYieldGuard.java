package ar.scraper.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares each site's product yield against its own previous run.
 *
 * <p>A scraper whose selectors stop matching does not throw. {@code
 * BaseScraper.ejecutar} returns {@code new ScrapeResult(sitio, prods, null,
 * ms)} whether {@code prods} holds 800 products or none, so a site can die and
 * the run still reports success. The pre-existing check in {@code
 * ScraperService} lists sites that returned exactly zero, at INFO level, which
 * misses the more common failure: a partial break that still returns a
 * plausible-looking handful.
 *
 * <p>Deliberately pure and history-free — the baseline is the previous
 * {@code conteoPorSitio}, which the catalogue already holds in memory and
 * rebuilds from the database on startup. No new table, no new query.
 */
public final class SiteYieldGuard {

    /**
     * Below this previous count a site is too small for a ratio to mean
     * anything: dropping from 4 products to 1 is noise.
     */
    static final int BASELINE_MINIMO = 20;

    /**
     * A site keeping less than this share of its previous yield is treated as
     * broken rather than quiet. Deliberately generous — real catalogues swing
     * with stock and seasonality, and an alert nobody trusts gets muted.
     */
    static final double UMBRAL_CAIDA = 0.5;

    public enum Severidad { CAIDA_TOTAL, CAIDA_PARCIAL }

    public record Alerta(String sitio, int previo, int actual, Severidad severidad) {
        public String mensaje() {
            return severidad == Severidad.CAIDA_TOTAL
                    ? "%s no devolvió productos (antes: %d). Probable scraper roto, no catálogo vacío."
                            .formatted(sitio, previo)
                    : "%s devolvió %d productos contra %d de la corrida anterior (%.0f%%)."
                            .formatted(sitio, actual, previo, 100.0 * actual / previo);
        }
    }

    private SiteYieldGuard() { }

    /**
     * @param previo    per-site counts from before this run
     * @param actual    per-site counts produced by this run
     * @param scrapeados sites this run actually visited; sites outside this set
     *                   are skipped, so a run limited to a subset never reports
     *                   the untouched ones as collapsed
     */
    public static List<Alerta> evaluar(Map<String, Integer> previo,
                                       Map<String, Integer> actual,
                                       Set<String> scrapeados) {
        if (previo == null || actual == null || scrapeados == null) return List.of();

        List<Alerta> alertas = new ArrayList<>();
        for (String sitio : scrapeados) {
            int antes = previo.getOrDefault(sitio, 0);
            if (antes < BASELINE_MINIMO) continue;

            int ahora = actual.getOrDefault(sitio, 0);
            if (ahora == 0) {
                alertas.add(new Alerta(sitio, antes, 0, Severidad.CAIDA_TOTAL));
            } else if (ahora < antes * UMBRAL_CAIDA) {
                alertas.add(new Alerta(sitio, antes, ahora, Severidad.CAIDA_PARCIAL));
            }
        }
        alertas.sort(Comparator.comparing(Alerta::sitio));
        return alertas;
    }

    /**
     * Folds yield alerts into the per-site error map so they travel the path
     * that already exists — {@code AggregatedResult.erroresPorSitio} reaches
     * {@code /api/data}'s {@code meta.errores} and is rendered by the UI. A log
     * line nobody opens is not detection.
     *
     * <p>An existing error always wins: if the site threw, that exception
     * explains the collapse better than the ratio does.
     */
    public static Map<String, String> fusionarEnErrores(Map<String, String> errores,
                                                        List<Alerta> alertas) {
        Map<String, String> salida = new java.util.LinkedHashMap<>();
        if (errores != null) salida.putAll(errores);
        if (alertas == null) return salida;

        for (Alerta a : alertas) salida.putIfAbsent(a.sitio(), a.mensaje());
        return salida;
    }
}
