package ar.scraper.outfits;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capacidad de persistencia del agregado {@code saved_outfits} y sus items: los outfits que el
 * usuario guardó con nombre.
 */
public interface SavedOutfitsPort {

    int guardarOutfit(UUID usuarioId, String nombre, String slotsJson, String suplementosJson,
                      double total);

    List<Map<String, Object>> obtenerOutfitsGuardados(UUID usuarioId);

    boolean eliminarOutfitGuardado(UUID usuarioId, int id);

    boolean renombrarOutfit(UUID usuarioId, int id, String nombre);
}
