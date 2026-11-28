package ar.scraper.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A scraper whose selectors stop matching does not throw. */
public final class SiteYieldGuard {

    static final int BASELINE_MINIMO = 20;

    /**
     * A site keeping less than this share of its previous yield is treated as broken rather than
     * quiet. Deliberately generous — real catalogues swing with stock and seasonality, and an alert
     * nobody trusts gets muted.
     */
    static final double UMBRAL_CAIDA = 0.5;

    /**
     * Prefix of a site error written when the store answered 403/429: an anti-bot block, not a
     * broken scraper. The frontend (blockedSites.js) classifies on the HTTP status that follows.
     */
    public static final String MARCA_BLOQUEO = "bloqueado por la tienda";

    private static final Pattern STATUS_BLOQUEO = Pattern.compile("\\bHTTP (403|429)\\b");

    /** The HTTP status (403 or 429) when {@code error} describes a store block, else null. */
    public static String statusDeBloqueo(String error) {
        if (error == null) return null;
        Matcher m = STATUS_BLOQUEO.matcher(error);
        return m.find() ? m.group(1) : null;
    }

    public enum Severidad { CAIDA_TOTAL, CAIDA_PARCIAL, BLOQUEO }

    /** {@code detalle} is the HTTP status for a {@link Severidad#BLOQUEO}, otherwise unused. */
    public record Alerta(String sitio, int previo, int actual, Severidad severidad, String detalle) {
        public Alerta(String sitio, int previo, int actual, Severidad severidad) {
            this(sitio, previo, actual, severidad, null);
        }

        public String mensaje() {
            return switch (severidad) {
                case BLOQUEO -> "%s %s (HTTP %s); sin productos esta corrida."
                        .formatted(sitio, MARCA_BLOQUEO, detalle);
                case CAIDA_TOTAL -> "%s no devolvió productos (antes: %d). Probable scraper roto, no catálogo vacío."
                        .formatted(sitio, previo);
                case CAIDA_PARCIAL -> "%s devolvió %d productos contra %d de la corrida anterior (%.0f%%)."
                        .formatted(sitio, actual, previo, 100.0 * actual / previo);
            };
        }
    }

    private SiteYieldGuard() { }

    public static List<Alerta> evaluar(Map<String, Integer> previo,
                                       Map<String, Integer> actual,
                                       Set<String> scrapeados) {
        return evaluar(previo, actual, scrapeados, Map.of());
    }

    /**
     * A site that gave nothing because the store blocked it ({@code errores} holds a 403/429) is
     * reported as a block, not as a probably broken scraper.
     */
    public static List<Alerta> evaluar(Map<String, Integer> previo,
                                       Map<String, Integer> actual,
                                       Set<String> scrapeados,
                                       Map<String, String> errores) {
        if (previo == null || actual == null || scrapeados == null) return List.of();

        List<Alerta> alertas = new ArrayList<>();
        for (String sitio : scrapeados) {
            int antes = previo.getOrDefault(sitio, 0);
            int ahora = actual.getOrDefault(sitio, 0);
            // A site that gave nothing is worth saying so from any positive baseline; the minimum
            // only filters partial drops, where a handful of products swings by noise.
            if (antes > 0 && ahora == 0) {
                String bloqueo = errores == null ? null : statusDeBloqueo(errores.get(sitio));
                alertas.add(bloqueo != null
                        ? new Alerta(sitio, antes, 0, Severidad.BLOQUEO, bloqueo)
                        : new Alerta(sitio, antes, 0, Severidad.CAIDA_TOTAL));
            } else if (antes >= BASELINE_MINIMO && ahora < antes * UMBRAL_CAIDA) {
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
