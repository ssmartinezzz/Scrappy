package ar.scraper.json;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * If the builder were inlined in both, a change to {@code deltaPct} would silently apply to one
 * chart and not the other, and nothing would go red.
 */
public final class HistorialJson {

    private HistorialJson() {}

    public static ObjectNode construir(List<Map<String, Object>> hist) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        var arr = node.putArray("puntos");
        hist.forEach(h -> {
            var p = arr.addObject();
            p.put("fecha",  (String) h.get("fecha"));
            p.put("precio", precioDe(h));
        });

        if (hist.size() >= 2) {
            double min   = hist.stream().mapToDouble(HistorialJson::precioDe).min().orElse(0);
            double max   = hist.stream().mapToDouble(HistorialJson::precioDe).max().orElse(0);
            double avg   = hist.stream().mapToDouble(HistorialJson::precioDe).average().orElse(0);
            double first = precioDe(hist.get(0));
            double last  = precioDe(hist.get(hist.size() - 1));
            node.put("min", min).put("max", max).put("avg", avg);
            node.put("deltaPct", first > 0 ? Math.round((last - first) / first * 1000.0) / 10.0 : 0);
        }
        return node;
    }

    private static double precioDe(Map<String, Object> fila) {
        return ((Number) fila.get("precio")).doubleValue();
    }
}
