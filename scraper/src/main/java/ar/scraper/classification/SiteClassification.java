package ar.scraper.classification;

import java.util.Set;

/**
 * Site/brand taxonomy sets + {@code sitioKey} normalization. {@code GYM_SITIOS}/{@code GYM_MARCAS}
 * stay: they are a tagging heuristic, not a transitive dependency of {@code productos} on
 * {@code sitio}.
 */
public final class SiteClassification {

    private SiteClassification() {}

    // Sitios 100% orientados a ropa/indumentaria de gimnasio Nota: la marca es "Monky" sin e — el
    // key debe matchear EXACTO el nombre configurado del sitio (GYM_SITIOS.contains(sitioKey) en
    // GymratTagger, no substring: un sitio hipotético "bulksupplements" no es Bulks)
    public static final Set<String> GYM_SITIOS = Set.of(
        "bulks", "fuark", "monkyforce", "fursten", "baesics"
    );

    // Canonical brand strings (ver MARCAS), comparados case-insensitive contra la marca ya
    // resuelta.
    public static final Set<String> GYM_MARCAS = Set.of(
        "nike", "adidas", "puma", "champion", "under armour", "reebok"
    );

    /**
     * The /picks Gymrat section is narrower than the gymrat tag: only the gym stores of
     * {@link #GYM_SITIOS} plus these brands, wherever they are sold. The tag itself stays broad
     * because the outfit builder's gym/casual gate reads it.
     */
    public static final Set<String> GYM_PICKS_MARCAS = Set.of("under armour");

    public static boolean esMarcaGymPicks(String sitio, String marca) {
        return GYM_SITIOS.contains(sitioKey(sitio))
                || (marca != null && GYM_PICKS_MARCAS.contains(marca.trim().toLowerCase()));
    }

    /** Normaliza el nombre de un sitio a su clave comparable (lowercase, sin no-alfanuméricos). */
    public static String sitioKey(String sitio) {
        return (sitio != null ? sitio : "").toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
