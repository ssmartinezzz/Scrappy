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
 *
 * <p>{@code TECH_SITIOS}, {@code SUPPL_SITIOS} and {@code SITIOS_PREMIUM} were
 * DELETED (close-1nf-and-3nf-foundation extension, design E1/E3) — their
 * substring-matching {@code sitioKey.contains(...)} readers moved to
 * equality lookups against {@link SiteRegistry}, which owns this data now
 * ({@code CODE-6}). {@code GYM_SITIOS}/{@code GYM_MARCAS} stay: they are a
 * tagging heuristic, not a transitive dependency of {@code productos} on
 * {@code sitio} (design's stated non-goal).</p>
 */
public final class SiteClassification {

    private SiteClassification() {}

    // Sitios 100% orientados a ropa/indumentaria de gimnasio
    // Nota: la marca es "Monky" sin e — el key debe matchear EXACTO el nombre
    // configurado del sitio (GYM_SITIOS.contains(sitioKey) en GymratTagger,
    // no substring: un sitio hipotético "bulksupplements" no es Bulks)
    public static final Set<String> GYM_SITIOS = Set.of(
        "bulks", "fuark", "monkyforce", "fursten"
    );

    // Marcas deportivas cuyo torso/piernas cuenta como gymrat aunque el nombre
    // no traiga keyword de training (user-confirmed). Canonical brand strings
    // (ver MARCAS), comparados case-insensitive contra la marca ya resuelta.
    // Nota: gym y casual son excluyentes por gymrat en OutfitService — estos
    // productos dejan de ser elegibles para el builder Casual.
    public static final Set<String> GYM_MARCAS = Set.of(
        "nike", "adidas", "puma", "champion", "under armour", "reebok"
    );

    /** Normaliza el nombre de un sitio a su clave comparable (lowercase, sin no-alfanuméricos). */
    public static String sitioKey(String sitio) {
        return (sitio != null ? sitio : "").toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
