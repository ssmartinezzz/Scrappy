package ar.scraper.aggregator.normalize;

import java.util.Set;

/**
 * Site/brand taxonomy sets + {@code sitioKey} normalization.
 *
 * <p>Extracted from {@code NormalizerService}'s {@code TECH_SITIOS},
 * {@code SUPPL_SITIOS}, {@code GYM_SITIOS}, {@code GYM_MARCAS},
 * {@code SITIOS_PREMIUM} fields plus the inline {@code sitioKey}
 * normalization expression from {@code normalizarProducto} (Work Unit 3 of
 * the aggregator SOLID modularization) — pure relocation, no behavior
 * change.</p>
 */
public final class SiteClassification {

    private SiteClassification() {}

    // Sitios cuyo rubro se fuerza independientemente de lo que traiga el scraper
    public static final Set<String> TECH_SITIOS = Set.of(
        "compragamer","fullh4rd","maximus","foreverbstrd",
        "compragamer.com","fullh4rd.com.ar","maximus.com.ar"
    );

    public static final Set<String> SUPPL_SITIOS = Set.of(
        "entreno","entreno.com.ar"
    );

    // Sitios 100% orientados a ropa/indumentaria de gimnasio
    public static final Set<String> GYM_SITIOS = Set.of(
        "bulks", "fuark"
    );

    // Marcas deportivas cuyo torso/piernas cuenta como gymrat aunque el nombre
    // no traiga keyword de training (user-confirmed). Canonical brand strings
    // (ver MARCAS), comparados case-insensitive contra la marca ya resuelta.
    // Nota: gym y casual son excluyentes por gymrat en OutfitService — estos
    // productos dejan de ser elegibles para el builder Casual.
    public static final Set<String> GYM_MARCAS = Set.of(
        "nike", "adidas", "puma", "champion", "under armour", "reebok"
    );

    // ══════════════════════════════════════════════════════════════════
    // SITIOS PREMIUM — tag transversal aditivo "marcaPremium"
    // Por sitio (tienda), no por marca extraída del nombre del producto:
    // la mayoría de los productos de una tienda premium no llevan el
    // nombre de la tienda en el título (ej. Harvey Willys vende "Soquete
    // Ozzy Black", no "Harvey Willys Ozzy Black").
    // NO altera ni reordena la cadena de prioridad de `badge` en ml_pipeline.py.
    // ══════════════════════════════════════════════════════════════════
    public static final Set<String> SITIOS_PREMIUM = Set.of(
        "harvey"
    );

    /** Normaliza el nombre de un sitio a su clave comparable (lowercase, sin no-alfanuméricos). */
    public static String sitioKey(String sitio) {
        return (sitio != null ? sitio : "").toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public static boolean esPremium(String sitioKey) {
        return SITIOS_PREMIUM.contains(sitioKey);
    }
}
