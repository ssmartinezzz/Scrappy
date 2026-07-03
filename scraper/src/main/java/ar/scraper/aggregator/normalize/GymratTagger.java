package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.springframework.stereotype.Component;

/**
 * Tag transversal "gymrat": ropa pensada para entrenar.
 *
 * <p>Extraído verbatim de {@code NormalizerService.esGymrat} + {@code KW_TRAINING_ROPA}
 * (Work Unit 8 de la modularización SOLID del aggregator) — pure relocation,
 * no behavior change. Usa {@link SiteClassification} y {@link CategoryGroups}.</p>
 */
@Component
public class GymratTagger {

    /**
     * Aditivo — NO altera categoria ni rubro.
     *
     * Reglas (OR), con guard de calzado:
     *   1) keyword de KW_TRAINING_ROPA en el nombre, O
     *   2) marca en GYM_MARCAS (nike, adidas, puma, champion, under armour, reebok), O
     *   3) sitio en GYM_SITIOS (bulks, fuark, monkyforce, fursten), O
     *   4) sitio == entreno Y el producto es indumentaria (no Suplemento/Alimentos)
     * Guard duro: si la categoria es calzado → false (gymrat es ROPA, no calzado).
     */
    public boolean esGymrat(String nombre, String sitioKey, String cat, String rubro, String marca) {
        if (CategoryGroups.esCalzado(cat)) return false;
        if ("suplementos".equals(rubro) || "Suplemento".equals(cat) || "Alimentos".equals(cat))
            return false;

        String n = AccentStripper.strip((nombre != null ? nombre : "").toLowerCase());

        if (GarmentTaxonomy.anyMatch(n, GarmentTaxonomy.KW_TRAINING_ROPA)) return true;
        if (marca != null && SiteClassification.GYM_MARCAS.contains(marca.trim().toLowerCase())) return true;
        if (SiteClassification.GYM_SITIOS.stream().anyMatch(sitioKey::contains)) return true;
        if (sitioKey.contains("entreno")) return true;
        return false;
    }
}
