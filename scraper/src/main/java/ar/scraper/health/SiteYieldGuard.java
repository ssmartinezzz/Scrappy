package ar.scraper.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A scraper whose selectors stop matching does not throw. */
public final class SiteYieldGuard {

    static final int BASELINE_MINIMO = 20;

    /**
     * A site keeping less than this share of its previous yield is treated as broken rather than
     * quiet. Deliberately generous — real catalogues swing with stock and seasonality, and an alert
     * nobody trusts gets muted.
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

    /** A log line nobody opens is not detection. */
    public static Map<String, String> fusionarEnErrores(Map<String, String> errores,
                                                        List<Alerta> alertas) {
        Map<String, String> salida = new java.util.LinkedHashMap<>();
        if (errores != null) salida.putAll(errores);
        if (alertas == null) return salida;

        for (Alerta a : alertas) salida.putIfAbsent(a.sitio(), a.mensaje());
        return salida;
    }
}
