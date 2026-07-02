package ar.scraper.aggregator.text;

/**
 * Shared accent-normalization regex chain (ADR-4).
 *
 * <p>This is the byte-identical 6-replacement chain duplicated, before this
 * extraction, across {@code GroupingService.normalizar},
 * {@code JaccardSimilarity}/{@code ProductIdentity}'s tokenizers,
 * {@code BrandExtractor.normalizarAcentos}, and {@code GymratTagger}'s inline
 * chain. Only the identical accent-stripping step is unified here — the
 * divergent stop-word/token filtering that each consumer layers on top stays
 * per-consumer (exploration flagged those as NOT byte-identical across
 * classes).</p>
 *
 * <p>Callers are responsible for lower-casing their input first, matching
 * the pre-extraction call sites (the regex patterns only target lowercase
 * accented characters).</p>
 */
public final class AccentStripper {

    private AccentStripper() {}

    public static String strip(String s) {
        return s.replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
    }
}
