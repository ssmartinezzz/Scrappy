package ar.scraper.ml;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * The ML output as the application keeps it: every field except the per-product {@code scores}, which
 * is about 150k entries and the bulk of the document.
 *
 * <p>Nothing reads those scores back. {@code /api/tendencias} needs {@code tendencias} and whether
 * {@code scores} is a non-empty object, and the rows that matter (score, badges, category) are
 * written to {@code productos} and {@code producto_badge} when the run persists. So {@code scores}
 * becomes {@code {"total": N}}: still a non-empty object when there were scores, still empty when
 * there were none.
 */
public final class MlOutputLiviano {

    static final String SCORES = "scores";
    static final String TOTAL = "total";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MlOutputLiviano() {}

    /** A copy of {@code mlOut} with its scores summarised; anything without an object of scores is returned as is. */
    public static JsonNode aligerar(JsonNode mlOut) {
        if (mlOut == null || !mlOut.isObject()) return mlOut;
        JsonNode scores = mlOut.path(SCORES);
        if (!scores.isObject() || yaResumido(scores)) return mlOut;
        ObjectNode liviano = JsonNodeFactory.instance.objectNode();
        mlOut.fields().forEachRemaining(campo -> liviano.set(campo.getKey(),
                SCORES.equals(campo.getKey()) ? resumen(scores.size()) : campo.getValue()));
        return liviano;
    }

    /**
     * Reads a stored payload without ever building the tree of scores: an old payload carries all of
     * them, a new one only the summary, and both come back in the same shape.
     */
    public static JsonNode leer(String payload) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(payload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return MAPPER.readTree(payload);
            ObjectNode raiz = JsonNodeFactory.instance.objectNode();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String campo = parser.currentName();
                JsonToken valor = parser.nextToken();
                if (SCORES.equals(campo) && valor == JsonToken.START_OBJECT) {
                    raiz.set(campo, scoresLeidos(parser));
                } else {
                    raiz.set(campo, MAPPER.readTree(parser));
                }
            }
            return raiz;
        }
    }

    /** Positioned on the opening brace of {@code scores}; leaves the parser on its closing one. */
    private static JsonNode scoresLeidos(JsonParser parser) throws IOException {
        int entradas = 0;
        Integer totalDeclarado = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String clave = parser.currentName();
            JsonToken valor = parser.nextToken();
            entradas++;
            if (entradas == 1 && TOTAL.equals(clave) && valor == JsonToken.VALUE_NUMBER_INT) {
                totalDeclarado = parser.getIntValue();
            } else if (valor.isStructStart()) {
                parser.skipChildren();
            }
        }
        return resumen(entradas == 1 && totalDeclarado != null ? totalDeclarado : entradas);
    }

    private static boolean yaResumido(JsonNode scores) {
        return scores.size() == 1 && scores.path(TOTAL).isInt();
    }

    private static JsonNode resumen(int total) {
        ObjectNode resumen = JsonNodeFactory.instance.objectNode();
        if (total > 0) resumen.put(TOTAL, total);
        return resumen;
    }
}
