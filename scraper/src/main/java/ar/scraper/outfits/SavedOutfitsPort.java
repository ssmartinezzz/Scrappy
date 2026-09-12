package ar.scraper.outfits;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capacidad de persistencia del agregado {@code saved_outfits} y sus items:
 * los outfits que el usuario guardó con nombre.
 *
 * <p>Las filas viajan como {@code List<Map<String,Object>>} porque así las
 * serializa el endpoint hoy — la forma del JSON es el contrato real y
 * envolverla en un record acá sólo agregaría una traducción en el medio.
 * Cuando esa forma se estabilice, el record va en esta misma área.</p>
 *
 * <p>La implementa un {@code @Repository} package-private de
 * {@code ar.scraper.db}.</p>
 */
public interface SavedOutfitsPort {

    int guardarOutfit(UUID usuarioId, String nombre, String slotsJson, String suplementosJson,
                      double total);

    List<Map<String, Object>> obtenerOutfitsGuardados(UUID usuarioId);

    boolean eliminarOutfitGuardado(UUID usuarioId, int id);

    boolean renombrarOutfit(UUID usuarioId, int id, String nombre);
}
