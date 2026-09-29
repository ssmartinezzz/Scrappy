package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders a {@code search_products} result as the chat answer. The model's own prose about
 * those rows proved unreliable (ignored them, invented advice), so the listing is built here
 * from the rows themselves. Only the markdown subset the chat renders: list items, links, bold.
 */
final class SearchAnswerRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchAnswerRenderer() {}

    /** The answer, or {@code null} when there is nothing to list (empty, malformed or not an array). */
    static String render(String searchJson) {
        if (searchJson == null) return null;
        JsonNode rows;
        try {
            rows = MAPPER.readTree(searchJson);
        } catch (Exception e) {
            return null;
        }
        if (rows == null || !rows.isArray() || rows.isEmpty()) return null;

        // Strict and relaxed selection are exclusive in the tool, so rows are all partial or none;
        // a mix is handled anyway by flagging each partial row instead of the header.
        boolean allPartial = true;
        Set<String> faltan = new LinkedHashSet<>();
        for (JsonNode r : rows) {
            if (isPartial(r)) r.path("terminosFaltantes").forEach(t -> faltan.add(t.asText()));
            else allPartial = false;
        }

        List<String> lines = new ArrayList<>();
        lines.add(allPartial
                ? "No encontré exactamente eso. Lo más parecido (faltan: " + String.join(", ", faltan) + "):"
                : "Encontré " + rows.size() + (rows.size() == 1 ? " producto:" : " productos:"));
        for (JsonNode r : rows) lines.add(line(r, !allPartial && isPartial(r)));
        return String.join("\n", lines);
    }

    private static boolean isPartial(JsonNode r) {
        return "parcial".equals(r.path("coincidencia").asText());
    }

    private static String line(JsonNode r, boolean flagPartial) {
        StringBuilder sb = new StringBuilder("- ");
        // A ']' in the name or a '(' / ')' / space in the url would end the link early (or make the
        // chat drop it): brackets go, url characters are percent-encoded.
        String nombre = r.path("nombre").asText("").replace("[", "").replace("]", "").replace("\n", " ").trim();
        String url = r.path("url").asText("").replace(" ", "%20").replace("(", "%28").replace(")", "%29");
        sb.append('[').append(nombre).append("](").append(url).append(')');
        String sitio = r.path("sitio").asText("");
        if (!sitio.isBlank()) sb.append(" — ").append(sitio);
        sb.append(" — ").append(pesos(r.path("precio").asDouble()));
        if (r.has("descuentoPct") && r.has("precioOrig")) {
            sb.append(" — **−").append(r.path("descuentoPct").asInt()).append("%** (antes ")
                    .append(pesos(r.path("precioOrig").asDouble())).append(')');
        }
        if (flagPartial) {
            List<String> f = new ArrayList<>();
            r.path("terminosFaltantes").forEach(t -> f.add(t.asText()));
            sb.append(" — parcial, faltan: ").append(String.join(", ", f));
        }
        return sb.toString();
    }

    /** es-AR: dot thousands, no decimals. */
    private static String pesos(double value) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.ROOT);
        sym.setGroupingSeparator('.');
        return "$" + new DecimalFormat("#,##0", sym).format(Math.round(value));
    }
}
