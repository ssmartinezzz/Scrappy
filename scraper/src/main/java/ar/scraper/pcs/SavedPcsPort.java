package ar.scraper.pcs;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capacidad de persistencia del agregado {@code saved_pcs} y sus items: los
 * builds del armador de PCs que el usuario guardó con nombre.
 *
 * <p>Mismo molde que {@code ar.scraper.outfits.SavedOutfitsPort}: las filas
 * viajan como {@code List<Map<String,Object>>} porque así las serializa el
 * endpoint, y {@link PcPick} entra ya armado — la conversión desde el JSON de
 * borde es responsabilidad del endpoint, no de este puerto.</p>
 *
 * <p>La implementa un {@code @Repository} package-private de
 * {@code ar.scraper.db}.</p>
 */
public interface SavedPcsPort {

    int guardarPc(UUID usuarioId, String nombre, List<PcPick> picks, double presupuesto,
                  boolean conGpu, double totalEstimado);

    List<Map<String, Object>> obtenerPcsGuardadas(UUID usuarioId);

    boolean eliminarPcGuardada(UUID usuarioId, int id);

    boolean renombrarPc(UUID usuarioId, int id, String nombre);
}
