package ar.scraper.pages;

import ar.scraper.aggregator.text.PrecioParser;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

final class CatalogJson {

    static final Set<String> HOMBRE = Set.of(
            "hombre","hombres","masculino","masculina","masculinos","masculinas","men","man","male",
            "caballero","caballeros","varones");
    static final Set<String> MUJER = Set.of(
            "mujer","mujeres","femenino","femenina","women","woman","female","dama","damas");
    static final Set<String> UNISEX = Set.of("unisex","unisexo","neutro");

    private static final Pattern NO_LETRA = Pattern.compile("[^\\p{L}]+");

    private CatalogJson() {}

    static Double precioComparado(JsonNode variante) {
        String compareStr = variante.path("compare_at_price").asText("");
        if ("null".equals(compareStr)) compareStr = "";
        OptionalDouble parsed = PrecioParser.parse(compareStr);
        return parsed.isPresent() ? parsed.getAsDouble() : null;
    }

    static List<String> textosNoVacios(JsonNode valores) {
        return textosNoVacios(valores, null);
    }

    static List<String> textosNoVacios(JsonNode valores, String campo) {
        List<String> out = new ArrayList<>();
        if (!valores.isArray()) return out;
        for (JsonNode v : valores) {
            String t = (campo == null ? v : v.path(campo)).asText("").trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    static void agregarTags(List<String> fuentes, JsonNode tags) {
        if (tags.isTextual()) {
            Arrays.stream(tags.asText("").split(","))
                    .map(String::trim).map(String::toLowerCase)
                    .forEach(fuentes::add);
        } else if (tags.isArray()) {
            for (JsonNode t : tags) fuentes.add(t.asText("").toLowerCase());
        }
    }

    static String genero(List<String> fuentes) {
        return genero(fuentes, HOMBRE, MUJER, UNISEX);
    }

    static String genero(List<String> fuentes, Set<String> hombre, Set<String> mujer, Set<String> unisex) {
        for (String f : fuentes) {
            if (unisex.stream().anyMatch(f::contains)) return "unisex";
        }
        boolean esHombre = fuentes.stream().anyMatch(f -> tienePalabra(f, hombre));
        boolean esMujer = fuentes.stream().anyMatch(f -> mujer.stream().anyMatch(f::contains));
        if (esHombre && !esMujer) return "hombre";
        if (esMujer && !esHombre) return "mujer";
        if (esHombre) return "unisex";
        return "";
    }

    /** Whole words only: "femenina" contains "men", "female" contains "male", "manga" contains "man". */
    static boolean tienePalabra(String texto, Set<String> palabras) {
        return NO_LETRA.splitAsStream(texto).anyMatch(palabras::contains);
    }
}
