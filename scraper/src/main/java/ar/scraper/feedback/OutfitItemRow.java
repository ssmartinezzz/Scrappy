package ar.scraper.feedback;

/**
 * Un puerto del área no puede devolver un tipo de {@code db} (regla {@code areasSonSumideros}), así
 * que el récord sube al área antes de que exista {@code FeedbackPort}.
 */
public record OutfitItemRow(String slot, String url, boolean liked, String estilo) {}
