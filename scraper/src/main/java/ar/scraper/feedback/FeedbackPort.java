package ar.scraper.feedback;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Capacidad de persistencia del agregado de señal de gusto:
 * {@code outfit_feedback_item} (likes y dislikes per-item, con estilo) y
 * {@code categoria_dismiss} ("no me interesa", feed-wide).
 *
 * <p>Son dos tablas y una sola señal: lo que el usuario aceptó y lo que
 * descartó. Las dos superficies que la leen —el armador de outfits y el feed
 * "Para ti"— la consultan juntas en cada request, así que partirla en dos
 * puertos habría duplicado el consumidor sin separar ningún ciclo de vida.</p>
 *
 * <p>La implementa un {@code @Repository} package-private de
 * {@code ar.scraper.db}: es {@code javac}, no ArchUnit, quien impide nombrar el
 * tipo concreto fuera de ese paquete.</p>
 */
public interface FeedbackPort {

    void guardarOutfitFeedbackItem(UUID usuarioId, String genero, String slot, String url,
                                   boolean liked, String estilo);

    List<OutfitItemRow> obtenerOutfitFeedback(UUID usuarioId);

    void limpiarOutfitFeedback(UUID usuarioId);

    void limpiarOutfitFeedback(UUID usuarioId, String estilo);

    void guardarCategoriaDismiss(UUID usuarioId, String categoria);

    void borrarCategoriaDismiss(UUID usuarioId, String categoria);

    Set<String> obtenerCategoriaDismiss(UUID usuarioId);
}
