package ar.scraper.web;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Serialises a price history into the JSON shape the frontend consumes:
 * {@code {puntos: [{fecha, precio}], min, max, avg, deltaPct}}.
 *
 * <p>Same reason {@link ProductJson} exists: two endpoints render this and they
 * must not drift. {@code /api/historial} feeds the sparkline and the detail
 * drawer; {@code /api/producto} feeds the standalone price-history page. If the
 * builder were inlined in both, a change to {@code deltaPct} would silently
 * apply to one chart and not the other, and nothing would go red.</p>
 *
 * <p>The stats appear only from two points on, and that is deliberate rather
 * than a guard against division by zero: a single observation has no minimum,
 * no maximum and no variation — it has a price. Emitting {@code min == max ==
 * avg == the only price} would dress one sighting up as a trend. The caller
 * renders "sin historial aún" off their absence.</p>
 */
final class HistorialJson {

    private HistorialJson() {}

    /** {@code hist} comes from {@code DatabaseService.cargarHistorial}, oldest first. */
    static ObjectNode construir(List<Map<String, Object>> hist) {
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
