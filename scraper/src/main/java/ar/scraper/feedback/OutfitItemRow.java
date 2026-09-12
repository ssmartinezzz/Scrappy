package ar.scraper.feedback;

/**
 * Fila cruda de feedback per-item — el join con el catálogo vivo lo hace el caller.
 *
 * <p>Vivía anidada en {@code ar.scraper.db.DatabaseService}. Un puerto del área
 * no puede devolver un tipo de {@code db} (regla {@code areasSonSumideros}), así
 * que el récord sube al área antes de que exista {@code FeedbackPort}.</p>
 */
public record OutfitItemRow(String slot, String url, boolean liked, String estilo) {}
