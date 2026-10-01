package ar.scraper.feedback;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Las dos superficies que la leen —el armador de outfits y el feed "Para ti"— la consultan juntas
 * en cada request, así que partirla en dos puertos habría duplicado el consumidor sin separar
 * ningún ciclo de vida.
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
