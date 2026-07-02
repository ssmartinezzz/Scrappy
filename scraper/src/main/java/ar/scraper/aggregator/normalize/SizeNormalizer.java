package ar.scraper.aggregator.normalize;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Size canonicalization.
 *
 * <p>Extraído verbatim de {@code NormalizerService.normalizarTalles}/
 * {@code normalizarTalle} + {@code TALLE_MAP} (Work Unit 6 de la
 * modularización SOLID del aggregator) — pure relocation, no behavior
 * change. {@code normalizarTalle} never called {@code capitalize} (it
 * upper-cases and strips non-alphanumeric characters directly), so there is
 * nothing to move from that utility here.</p>
 */
@Component
public class SizeNormalizer {

    private static final Map<String, String> TALLE_MAP = new LinkedHashMap<>();
    static {
        TALLE_MAP.put("extra small", "XS"); TALLE_MAP.put("extrasmall","XS"); TALLE_MAP.put("xxs","XXS");
        TALLE_MAP.put("small","S");   TALLE_MAP.put("chico","S");   TALLE_MAP.put("ch","S");
        TALLE_MAP.put("medium","M");  TALLE_MAP.put("mediano","M"); TALLE_MAP.put("med","M");
        TALLE_MAP.put("large","L");   TALLE_MAP.put("grande","L");  TALLE_MAP.put("gr","L");
        TALLE_MAP.put("extra large","XL"); TALLE_MAP.put("extralarge","XL");
        TALLE_MAP.put("xxlarge","XXL"); TALLE_MAP.put("extra extra large","XXL");
        TALLE_MAP.put("xxxlarge","XXXL"); TALLE_MAP.put("3xl","XXXL"); TALLE_MAP.put("3 xl","XXXL");
        TALLE_MAP.put("talle unico","Único"); TALLE_MAP.put("unico","Único"); TALLE_MAP.put("unique","Único");
        TALLE_MAP.put("default title","");
    }

    public List<String> normalizar(List<String> talles) {
        if (talles == null) return List.of();
        return talles.stream()
                .map(this::normalizarTalle)
                .filter(t -> !t.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalizarTalle(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.trim().toLowerCase();
        String mapped = TALLE_MAP.get(lower);
        if (mapped != null) return mapped;
        // Devolver el talle original en mayúsculas si parece válido
        String clean = raw.trim().toUpperCase().replaceAll("[^A-Z0-9./]", "");
        return clean.length() <= 6 ? clean : "";
    }
}
