package ar.scraper.financiacion;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the financiacion_presets aggregate.
 *
 * <p>Extracted like {@code ar.scraper.favoritos.FavoritosPort} (extract-favoritos-port)
 * so {@code ar.scraper.web} and {@code ar.scraper.ml} depend on this port, not on
 * {@code DatabaseService} directly (extract-preset-historial-ports).</p>
 */
public interface PresetPort {

    /**
     * En el primer arranque (tabla vacía), crea un preset ilustrativo marcado
     * explícitamente como ejemplo y lo deja activo. Llamado desde
     * {@code DatabaseService.init()}.
     */
    void seedPresetIlustrativoSiVacio() throws SQLException;

    List<Preset> listarPresets();

    Optional<Preset> cargarPresetActivo();

    /**
     * Crea un preset nuevo, inactivo por defecto. Retorna el id generado, o -1 en
     * error o si {@code cuotas}/{@code recargoPct} son inválidos.
     */
    int crearPreset(String label, double recargoPct, int cuotas);

    /**
     * Edita label/recargoPct/cuotas de un preset existente. No altera su estado activo.
     */
    boolean editarPreset(int id, String label, double recargoPct, int cuotas);

    /** Activa el preset {@code id} y desactiva todos los demás, de forma transaccional. */
    boolean activarPreset(int id);

    /**
     * Elimina un preset. Si era el ÚNICO restante, recrea el preset ilustrativo
     * activo; si quedan otros, ninguno se auto-activa.
     */
    boolean eliminarPreset(int id);
}
