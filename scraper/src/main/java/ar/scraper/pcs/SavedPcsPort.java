package ar.scraper.pcs;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capacidad de persistencia del agregado {@code saved_pcs} y sus items: los builds del armador de
 * PCs que el usuario guardó con nombre.
 */
public interface SavedPcsPort {

    int guardarPc(UUID usuarioId, String nombre, List<PcPick> picks, double presupuesto,
                  boolean conGpu, double totalEstimado, Gama gama);

    List<Map<String, Object>> obtenerPcsGuardadas(UUID usuarioId);

    boolean eliminarPcGuardada(UUID usuarioId, int id);

    boolean renombrarPc(UUID usuarioId, int id, String nombre);
}
